package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleCoreTest {
    static CandleUpdate bar(int index, boolean closed, double close) {
        long time=index*300000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",time,100,Math.max(110,close),90,close),5,closed,time+(closed?300000:1000));
    }
    @Test void orderedUpdatesCloseOnceAndNeverDuplicate() {
        var p=new CandlePipeline("WINZ26",5); var events=new ArrayList<CandleUpdate>(); p.subscribe(events::add);
        p.accept(bar(0,false,100)); p.accept(bar(0,false,105)); p.accept(bar(0,true,105));
        assertThat(p.accept(bar(0,true,105))).isFalse(); p.accept(bar(1,true,106));
        assertThat(p.snapshot()).hasSize(2); assertThat(events.stream().filter(CandleUpdate::closed).count()).isEqualTo(2);
        assertThatThrownBy(() -> p.accept(bar(0,true,105))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSeriesMixingPrematureClosureAndClosedCorrections() {
        var p=new CandlePipeline("WINZ26",5); p.accept(bar(0,true,100));
        assertThatThrownBy(() -> p.accept(bar(0,true,101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandleUpdate(bar(0,true,100).candle(),5,true,299999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandlePipeline("WINV26",5).accept(bar(0,true,100))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandlePipeline("WINZ26",15).accept(bar(0,true,100))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cannotAppendOverFormingCandle() {
        var p=new CandlePipeline("WINZ26",5); p.accept(bar(0,false,100));
        assertThatThrownBy(() -> p.accept(bar(1,true,100))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aggregationRequiresCompleteContiguousClosedGroups() {
        var complete=CandleAggregation.aggregate(List.of(bar(0,true,101),bar(1,true,102),bar(2,true,103)),15);
        assertThat(complete).hasSize(1); assertThat(complete.getFirst().closed()).isTrue();
        assertThat(complete.getFirst().availableAtMsc()).isEqualTo(900000);
        assertThat(complete.getFirst().candle().close()).isEqualTo(103);
        assertThat(CandleAggregation.aggregate(List.of(bar(0,true,100),bar(2,true,100)),15)).isEmpty();
        assertThat(CandleAggregation.aggregate(List.of(bar(1,true,100),bar(2,true,100)),15)).isEmpty();
    }
    @Test void aggregationKeepsFormingDistinctFromClosed() {
        var values=CandleAggregation.aggregate(List.of(bar(0,true,100),bar(1,false,105)),15);
        assertThat(values).hasSize(1); assertThat(values.getFirst().closed()).isFalse();
    }
    @Test void replayNeverRevealsFutureToPipelineOrDetector() {
        var replay=new ThreeCandleReplay("WINZ26",5,List.of(bar(0,true,100),bar(1,true,101),bar(2,true,102)),300000);
        var observed=new ArrayList<CandleUpdate>(); replay.pipeline().subscribe(observed::add);
        assertThat(replay.pipeline().snapshot()).containsExactly(bar(0,true,100));
        assertThat(observed).isEmpty(); replay.next();
        assertThat(observed).containsExactly(bar(1,true,101));
        assertThat(replay.pipeline().snapshot()).hasSize(2);
    }
    @Test void replayPlayPauseSpeedNextResetAndEndAreDeterministic() {
        var replay=new ThreeCandleReplay("WINZ26",5,List.of(bar(0,true,100),bar(1,true,101),bar(2,true,102)),0);
        replay.play(0); replay.advance(500000000); assertThat(replay.pipeline().snapshot()).isEmpty();
        replay.pause(500000000); replay.advance(2000000000); assertThat(replay.pipeline().snapshot()).isEmpty();
        replay.next(); assertThat(replay.pipeline().snapshot()).hasSize(1);
        replay.speed(2,2000000000); replay.play(2000000000); replay.advance(3000000000L);
        assertThat(replay.status()).isEqualTo(ThreeCandleReplay.Status.COMPLETED);
        assertThat(replay.pipeline().snapshot()).hasSize(3); replay.next(); assertThat(replay.pipeline().snapshot()).hasSize(3);
        replay.reset(); assertThat(replay.pipeline().snapshot()).isEmpty(); assertThat(replay.status()).isEqualTo(ThreeCandleReplay.Status.PAUSED);
    }
    @Test void sameClosedSequenceReachesSamePipelineFromLiveAndReplay() {
        var bars=List.of(bar(0,true,101),bar(1,true,102),bar(2,true,103));
        var live=new CandlePipeline("WINZ26",5); bars.forEach(live::accept);
        var replay=new ThreeCandleReplay("WINZ26",5,bars,0); bars.forEach(ignored -> replay.next());
        assertThat(replay.pipeline().snapshot()).isEqualTo(live.snapshot());
    }
    @Test void invalidReplaySeriesIsRejected() {
        assertThatThrownBy(() -> new ThreeCandleReplay("WINZ26",5,List.of(bar(1,true,100),bar(0,true,100)),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThreeCandleReplay("WINZ26",5,List.of(bar(0,false,100)),0)).isInstanceOf(IllegalArgumentException.class);
    }
}
