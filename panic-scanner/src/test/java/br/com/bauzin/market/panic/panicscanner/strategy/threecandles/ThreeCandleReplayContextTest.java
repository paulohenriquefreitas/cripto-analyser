package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.H1SessionPreparation;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleReplayContextTest {
    private long time(String date, int hour) {
        return LocalDate.parse(date).atTime(hour,0).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
    private CandleUpdate bar(String date, int hour) {
        long t=time(date,hour);
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,100,110,90,105),5,true,t+300000);
    }
    private ThreeCandleReplay replay(List<CandleUpdate> bars, int hour) {
        var date=LocalDate.parse("2026-10-26");
        var plan=H1SessionPreparation.prepare("WINZ26",date,time(date.toString(),9),bars);
        return new ThreeCandleReplay("WINZ26",5,bars,time(date.toString(),hour),plan);
    }
    @Test void mondayExposesThreePreviousSessionsButKeepsOlderWarmupInternal() {
        var thursday=bar("2026-10-22",9); var friday=bar("2026-10-23",9);
        var monday=bar("2026-10-26",9); var later=bar("2026-10-26",10);
        var bars=List.of(bar("2026-10-20",9),bar("2026-10-21",9),thursday,friday,monday,later);
        var wednesday=bars.get(1);
        var replay=replay(bars,9);
        assertThat(replay.candles()).containsExactly(wednesday,thursday,friday);
        assertThat(replay.pipeline().snapshot()).hasSize(4);
        replay.next();
        assertThat(replay.candles()).containsExactly(wednesday,thursday,friday,monday);
        assertThat(replay.availableAtMsc()).isEqualTo(monday.availableAtMsc());
        assertThat(replay.candles()).allMatch(c -> c.availableAtMsc()<=replay.availableAtMsc());
        replay.reset(); assertThat(replay.candles()).containsExactly(wednesday,thursday,friday);
        var midday=replay(bars,10);
        assertThat(midday.candles()).containsExactly(wednesday,thursday,friday,monday);
        midday.next(); assertThat(midday.candles()).containsExactly(wednesday,thursday,friday,monday,later);
    }
    @Test void absentSessionIsNeverSynthesizedAndHolidayGapUsesActualData() {
        var thursday=bar("2026-10-22",9); var monday=bar("2026-10-26",9);
        var incomplete=replay(List.of(thursday,monday),9);
        assertThat(incomplete.candles()).containsExactly(thursday);
        var wednesday=bar("2026-10-21",9);
        assertThat(replay(List.of(wednesday,thursday,monday),9).candles()).containsExactly(wednesday,thursday);
    }
}
