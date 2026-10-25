package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VolumeDeltaTrackerTest {
    private MarketTrade trade(long time, double volume, AggressorSide side) {
        return new MarketTrade("WINZ26", time, 200000, volume, side);
    }

    @Test void rollingDeltaExpiresAtExactBoundaryWhileCvdKeepsTheSession() {
        var tracker = new VolumeDeltaTracker(500, 5000);
        tracker.appendTick(trade(1000, 10, AggressorSide.BUY), 1000);
        tracker.appendTick(trade(1100, 4, AggressorSide.SELL), 1100);
        var result = tracker.appendTick(trade(1200, 7, AggressorSide.AMBIGUOUS), 1200);
        assertThat(result.delta()).isEqualTo(6);
        assertThat(result.ambiguousVolume()).isEqualTo(7);
        assertThat(tracker.snapshot(1499).buyVolume()).isEqualTo(10);
        assertThat(tracker.snapshot(1500).delta()).isEqualTo(-4);
        var idle = tracker.snapshot(1700);
        assertThat(idle.delta()).isZero();
        assertThat(idle.events()).isZero();
        assertThat(idle.cumulativeDelta()).isEqualTo(6);
        assertThat(idle.quality()).isEqualTo(VolumeDeltaTracker.Quality.NO_TRADES);
    }

    @Test void reportsCapacityLossWithoutHidingItAndCvdStillCountsEveryTrade() {
        var tracker = new VolumeDeltaTracker(500, 2);
        for (int i = 0; i < 3; i++) tracker.appendTick(trade(i, 1, AggressorSide.BUY), i);
        var full = tracker.latest();
        assertThat(full.delta()).isEqualTo(2);
        assertThat(full.cumulativeDelta()).isEqualTo(3);
        assertThat(full.processedTicks()).isEqualTo(3);
        assertThat(full.complete()).isFalse();
        assertThat(full.quality()).isEqualTo(VolumeDeltaTracker.Quality.CAPACITY_EXCEEDED);
        assertThat(tracker.snapshot(500).complete()).isTrue();
    }

    @Test void neverTruncatesFractionalOrOutOfLongRangeContracts() {
        for (double volume : new double[]{1.5, 0x1p63}) {
            var tracker = new VolumeDeltaTracker(500, 5000);
            var result = tracker.appendTick(trade(1000, volume, AggressorSide.BUY), 1000);
            assertThat(result.delta()).isZero();
            assertThat(result.complete()).isFalse();
            assertThat(result.cumulativeComplete()).isFalse();
            assertThat(result.quality()).isEqualTo(VolumeDeltaTracker.Quality.INVALID_VOLUME);
            assertThat(tracker.snapshot(1500).complete()).isTrue();
            assertThat(tracker.latest().cumulativeComplete()).isFalse();
        }
    }

    @Test void invalidVolumeAndRegressingInputAreExplicit() {
        var tracker = new VolumeDeltaTracker(500, 5000);
        assertThat(tracker.invalidVolume(1000, 1000).quality()).isEqualTo(VolumeDeltaTracker.Quality.INVALID_VOLUME);
        assertThatThrownBy(() -> tracker.appendTick(trade(999, 1, AggressorSide.BUY), 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracker.appendTick(trade(1001, 1, AggressorSide.BUY), 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sharedEngineDoesNotCountDuplicateIdentityButKeepsDistinctTradesAtSameTime() {
        var engine = new MonitoringFlowEngine("WINZ26");
        var event = trade(1000, 3, AggressorSide.BUY);
        assertThat(engine.onTrade(event, 1)).isTrue();
        assertThat(engine.onTrade(event, 1)).isFalse();
        assertThat(engine.onTrade(event, 2)).isTrue();
        var value = engine.snapshot(1000).volumeDelta();
        assertThat(value.processedTicks()).isEqualTo(2);
        assertThat(value.delta()).isEqualTo(6);
        assertThat(value.events()).isEqualTo(2);
    }
}
