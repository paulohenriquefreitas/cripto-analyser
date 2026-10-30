package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistory;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="threecandles.historyRoot",matches=".+")
class ThreeCandleLocalHistorySmokeTest {
    @Test void replaysAnExistingRealDayWithoutMt5OrFutureExposure() throws Exception {
        var provider=new ReplayHistoryProvider(System.getProperty("threecandles.historyRoot")+"/{symbol}/{date}.ndjson","unused","unused");
        var adapter=new ThreeCandleHistory(provider,new ObjectMapper());
        var date=LocalDate.of(2026,10,20);
        long start=date.atTime(9,0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli();
        for (int minutes : new int[]{5,15}) {
            var history=adapter.loadWithStructuralWarmup("WIN$N",date,minutes);
            assertThat(history).isNotEmpty();
            var replay=new ThreeCandleReplay("WIN$N",minutes,history,start);
            assertThat(replay.pipeline().snapshot()).allMatch(c -> c.availableAtMsc()<=start);
            assertThat(replay.pipeline().activeLevels()).allMatch(l -> l.confirmedAtMsc()<=start);
            int initial=replay.pipeline().snapshot().size();
            for(int i=initial;i<history.size();i++) {
                replay.next(); assertThat(replay.pipeline().snapshot()).hasSize(i+1);
                assertThat(replay.pipeline().snapshot().getLast()).isEqualTo(history.get(i));
                long knownAt=history.get(i).availableAtMsc();
                assertThat(replay.pipeline().activeLevels()).allMatch(l -> l.confirmedAtMsc()<=knownAt);
            }
            assertThat(replay.status()).isEqualTo(ThreeCandleReplay.Status.COMPLETED);
            System.out.println("THREE_CANDLES_REAL_HISTORY symbol=WIN$N date="+date+" minutes="+minutes+" bars="+history.size()+" initialVisible="+initial);
            if (minutes==5 && System.getProperty("threecandles.previewFile")!=null) {
                var preview=new ThreeCandleReplay("WIN$N",5,history,Instant.parse("2026-10-20T16:00:00Z").toEpochMilli());
                new ObjectMapper().writeValue(java.nio.file.Path.of(System.getProperty("threecandles.previewFile")).toFile(),
                        java.util.Map.of("id","visual-preview","symbol","WIN$N","timeframeMinutes",5,"status","PAUSED","speed",1,
                                "candles",preview.pipeline().snapshot().stream().filter(c -> c.candle().bucketStartTimeMsc() >= date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli()).toList(),"structure",preview.pipeline().structure(6)));
            }
        }
    }
}
