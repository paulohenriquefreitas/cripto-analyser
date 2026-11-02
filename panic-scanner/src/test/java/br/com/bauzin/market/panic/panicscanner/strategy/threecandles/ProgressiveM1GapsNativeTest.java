package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgressiveM1GapsNativeTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA=Path.of("src/test/resources/threecandles/progressive-gaps");
    @TempDir Path cache;
    @Test void september16MissingOpeningNoLongerBlocksReplayAndM5DowAndFiftyRemainExact() throws Exception {
        String symbol="WIN$N";var day=LocalDate.parse("2026-09-16");long start=day.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var rates=Arrays.asList(JSON.readValue(DATA.resolve("WIN_N-2026-09-16-M5.json").toFile(),Mt5Candle[].class));
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WIN_N-2026-09-16-M1.json").toFile(),Mt5Candle[].class));
        var official=rates.stream().map(m->new CandleUpdate(new IntrabarCandleSnapshot(symbol,m.time()*1000,m.open(),m.high(),m.low(),m.close()),5,true,m.time()*1000+300000)).toList();
        var built=ProgressiveM1History.buildDetailed(symbol,official,minutes,start);
        assertThat(minutes).hasSize(563);assertThat(built.missingM1Times()).containsExactly(start,start+60000);
        var plan=H1SessionPreparation.prepare(symbol,day,start,official);
        var replay=new ThreeCandleReplay(symbol,5,built.updates(),start,plan,ThreeCandleReplay.Mode.PROGRESSIVE_M1,built.missingM1Times());
        var closed=new ThreeCandleReplay(symbol,5,official,start,plan);
        replay.next();assertThat(replay.availableAtMsc()).isEqualTo(start+300000);assertThat(replay.candles().getLast().closed()).isTrue();
        replay.reset();int closes=0;
        while(replay.status()!=ThreeCandleReplay.Status.COMPLETED) {
            replay.next();long at=replay.availableAtMsc();
            assertThat(replay.candles()).allMatch(c->c.availableAtMsc()<=at);
            var rebuilt=new ThreeCandleReplay(symbol,5,built.updates(),at,plan,ThreeCandleReplay.Mode.PROGRESSIVE_M1,built.missingM1Times());
            assertThat(replay.pipeline().snapshot()).isEqualTo(rebuilt.pipeline().snapshot());
            if(replay.candles().getLast().closed()) {
                closed.next();closes++;assertThat(replay.candles()).isEqualTo(closed.candles());
                assertThat(replay.pipeline().structure(6)).isEqualTo(closed.pipeline().structure(6));
                var dow=DowStructureEngine.analyze(symbol,replay.candles(),replay.pipeline().structure(6),at);
                var expected=DowStructureEngine.analyze(symbol,closed.candles(),closed.pipeline().structure(6),at);
                assertThat(dow).isEqualTo(expected);
                assertThat(DowFiftyEngine.analyze(symbol,replay.candles(),dow,at)).isEqualTo(DowFiftyEngine.analyze(symbol,closed.candles(),expected,at));
            }
        }
        assertThat(closes).isEqualTo(113);assertThat(replay.missingM1Times()).containsExactly(start,start+60000);
        var mt5=mock(Mt5ProcessClient.class);when(mt5.readCandles(symbol,"M5",day.minusDays(28),day)).thenReturn(rates);
        when(mt5.readCandles(symbol,"M1",day,day)).thenReturn(minutes);
        var paths=new ReplayHistoryProvider(cache+"/{symbol}/{date}.ndjson","unused","unused");var history=new ThreeCandleHistory(paths,JSON);
        var loader=new ThreeCandleHistoryLoader(history,paths,mt5,JSON);
        var controller=new ThreeCandlesController(history,mock(ThreeCandleLive.class),new Mt5Symbol("WINZ26"),loader);
        var view=controller.create(new ThreeCandlesController.Start(symbol,day.toString(),"09:00",5,CandleTimeBasis.MT5_WALL_CLOCK,6,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1));
        var export=Path.of("../frontend/src/features/threecandles/validation/progressive-gaps");Files.createDirectories(export);
        JSON.writerWithDefaultPrettyPrinter().writeValue(export.resolve("0900.json").toFile(),view);
        assertThat(view.availableAtMsc()).isEqualTo(start);assertThat(view.missingM1Times()).containsExactly(start,start+60000);
        view=controller.command(view.id(),new ThreeCandlesController.Command("NEXT",null),6);
        assertThat(view.availableAtMsc()).isEqualTo(start+300000);assertThat(view.candles().getLast().closed()).isTrue();
        JSON.writerWithDefaultPrettyPrinter().writeValue(export.resolve("0905.json").toFile(),view);
        view=controller.command(view.id(),new ThreeCandlesController.Command("NEXT",null),6);
        assertThat(view.availableAtMsc()).isEqualTo(start+360000);assertThat(view.candles().getLast().closed()).isFalse();
        JSON.writerWithDefaultPrettyPrinter().writeValue(export.resolve("0906.json").toFile(),view);
        verify(mt5,times(1)).readCandles(symbol,"M1",day,day);
    }
}
