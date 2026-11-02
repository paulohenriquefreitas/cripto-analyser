package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleWickNativeTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA=Path.of("src/test/resources/threecandles/pattern-detector");
    List<CandleUpdate> read(String name) throws Exception {
        var result=new ArrayList<CandleUpdate>();
        for(var c:JSON.readValue(DATA.resolve(name).toFile(),Mt5Candle[].class))
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",c.time()*1000,c.open(),c.high(),c.low(),c.close()),5,true,c.time()*1000+300000));
        return result;
    }
    @Test void bothNativeSessionsUseOnlyQualifiedWicksAndExportEveryRejectionWithoutPerformanceClaims() throws Exception {
        var export=Path.of("../frontend/src/features/threecandles/validation/pattern-wick-v4");Files.createDirectories(export);
        for(String date:List.of("2026-10-06","2026-10-30")) {
            var all=new ArrayList<CandleUpdate>();
            if(date.endsWith("06"))all.addAll(read("WINZ26-2026-10-06-M5-with-warmup.json"));
            else{all.addAll(read("WINZ26-warmup-M5.json"));all.addAll(read("WINZ26-2026-10-30-M5.json"));}
            var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-"+date+"-M1.json").toFile(),Mt5Candle[].class));
            long opening=LocalDate.parse(date).atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
            var prep=H1SessionPreparation.prepare("WINZ26",LocalDate.parse(date),opening,all);
            var result=ThreeCandlePatternStudy.analyze("WINZ26",date,all,minutes,prep,all.getLast().availableAtMsc());
            assertThat(result.parameters().minRejectionTicks()).isEqualTo(6);
            for(var c:result.candidates()) {
                assertThat(c.events()).allMatch(e->e.atMsc()<=result.availableAtMsc());
                if(c.trigger().placedAtMsc()!=null){
                    assertThat(c.rejectionWick()).isNotNull();assertThat(c.rejectionWick().points()).isGreaterThanOrEqualTo(30);
                    assertThat(c.rejectionWick().availableAtMsc()).isEqualTo(c.trigger().placedAtMsc());
                }
                if(c.trigger().reachedAtMsc()!=null)assertThat(c.trigger().reachedAtMsc()).isGreaterThan(c.rejectionWick().availableAtMsc());
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(export.resolve(date+"-study.json").toFile(),result);
        }
    }
}
