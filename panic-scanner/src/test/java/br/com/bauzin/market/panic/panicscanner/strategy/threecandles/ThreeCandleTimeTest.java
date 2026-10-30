package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleTimeBasis;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleTimeTest {
    @Test void terminalNineMapsToTheRawNineAxisNotRawTwelve() {
        var date=LocalDate.of(2026,10,20);
        long start=CandleTimeBasis.MT5_WALL_CLOCK.replayStart(date,LocalTime.of(9,0));
        assertThat(start).isEqualTo(Instant.parse("2026-10-20T09:00:00Z").toEpochMilli());
        assertThat(CandleTimeBasis.UTC_INSTANT.replayStart(date,LocalTime.of(9,0)))
                .isEqualTo(Instant.parse("2026-10-20T12:00:00Z").toEpochMilli());
    }
    @Test void selectedTerminalTimeDoesNotPreloadThreeHoursOfFutureCandles() {
        long start=CandleTimeBasis.MT5_WALL_CLOCK.replayStart(LocalDate.of(2026,10,20),LocalTime.of(9,0));
        var bar=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",start,100,110,90,100),5,true,start+300000);
        var replay=new ThreeCandleReplay("WINZ26",5,List.of(bar),start);
        assertThat(replay.pipeline().snapshot()).isEmpty();
        replay.next();
        assertThat(replay.pipeline().snapshot()).containsExactly(bar);
        assertThat(bar.candle().bucketStartTimeMsc()).isEqualTo(start);
    }
}
