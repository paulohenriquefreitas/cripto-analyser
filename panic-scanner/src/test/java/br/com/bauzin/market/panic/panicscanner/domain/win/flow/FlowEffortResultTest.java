package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalDouble;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.FlowAtTimeProvider;
import br.com.bauzin.market.panic.panicscanner.application.win.flow.RecentTradeBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class FlowEffortResultTest {
    @Test
    void sellDominantFallHasPositiveAlignedResult() {
        FlowEffortResult result = result(10, -8, 100, 90, 95);

        assertThat(result.alignedResult()).hasValue(10);
        assertThat(result.opposingPressure()).hasValue(0);
    }

    @Test
    void sellDominantRiseHasNegativeAlignedResultAndOpposingPressure() {
        FlowEffortResult result = result(10, -8, 100, 110, 105);

        assertThat(result.alignedResult()).hasValue(-10);
        assertThat(result.opposingPressure()).hasValue(10);
    }

    @Test
    void buyDominantRiseHasPositiveAlignedResult() {
        FlowEffortResult result = result(10, 8, 100, 110, 105);

        assertThat(result.alignedResult()).hasValue(10);
        assertThat(result.opposingPressure()).hasValue(0);
    }

    @Test
    void buyDominantFallHasNegativeAlignedResultAndOpposingPressure() {
        FlowEffortResult result = result(10, 8, 100, 90, 95);

        assertThat(result.alignedResult()).hasValue(-10);
        assertThat(result.opposingPressure()).hasValue(10);
    }

    @Test
    void zeroDeltaMakesDirectionalEfficiencyUnavailable() {
        FlowEffortResult result = result(10, 0, 100, 100, 100);

        assertThat(result.efficiency()).isEmpty();
        assertThat(result.alignedResult()).isEmpty();
        assertThat(result.opposingPressure()).isEmpty();
    }

    @Test
    void fullyAmbiguousVolumeHasNoDirectionalCoverageOrEfficiency() {
        FlowSnapshot snapshot = snapshot(0, 0, 10, 100, 110, 105);

        FlowEffortResult result = FlowEffortResult.from(snapshot);

        assertThat(result.directionalImbalance()).isEmpty();
        assertThat(result.directionalCoverage()).hasValue(0);
        assertThat(result.efficiency()).isEmpty();
        assertThat(result.ambiguousShare()).hasValue(1);
    }

    @Test
    void derivesEachWindowIndependently() {
        FlowSnapshotSet snapshots = new FlowSnapshotSet(
                snapshot(1, 0, 0, 100, 101, 101),
                snapshot(0, 2, 0, 100, 98, 100),
                snapshot(3, 0, 0, 100, 103, 103),
                snapshot(0, 4, 0, 100, 96, 100),
                1_000);

        FlowEffortResultSet results = FlowEffortResultSet.from(snapshots);

        assertThat(results.oneSecond().knownDelta()).isEqualTo(1);
        assertThat(results.threeSeconds().knownDelta()).isEqualTo(-2);
        assertThat(results.fiveSeconds().knownDelta()).isEqualTo(3);
        assertThat(results.tenSeconds().knownDelta()).isEqualTo(-4);
    }

    @Test
    void causalSnapshotAtEventTimeExcludesLaterTradeBeforeEffortCalculation() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(new MarketTrade("WINV26", 1_000, 100, 2, AggressorSide.SELL));
        buffer.add(new MarketTrade("WINV26", 1_001, 99, 3, AggressorSide.SELL));
        buffer.add(new MarketTrade("WINV26", 1_002, 200, 100, AggressorSide.BUY));

        FlowSnapshotSet snapshots = new FlowAtTimeProvider(buffer).snapshotAt("WINV26", 1_001);
        FlowEffortResult result = FlowEffortResult.from(snapshots.oneSecond());

        assertThat(snapshots.maxTradeTimeMscUsed()).isEqualTo(1_001);
        assertThat(result.knownDelta()).isEqualTo(-5);
        assertThat(result.priceResult()).hasValue(-1);
    }

    private static FlowEffortResult result(
            double knownVolume, double knownDelta, double firstPrice, double lastPrice, double maxPrice) {
        double buy = Math.max(0, (knownVolume + knownDelta) / 2);
        double sell = Math.max(0, (knownVolume - knownDelta) / 2);
        return FlowEffortResult.from(snapshot(buy, sell, 0, firstPrice, lastPrice, maxPrice));
    }

    private static FlowSnapshot snapshot(
            double buyVolume, double sellVolume, double ambiguousVolume,
            double firstPrice, double lastPrice, double maxPrice) {
        double total = buyVolume + sellVolume + ambiguousVolume;
        double known = buyVolume + sellVolume;
        double delta = buyVolume - sellVolume;
        return new FlowSnapshot(
                Duration.ofSeconds(1), 0, 1_000, 1, total, buyVolume, sellVolume, ambiguousVolume,
                known, delta, known == 0 ? 0 : buyVolume / known, known == 0 ? 0 : sellVolume / known,
                1, total, OptionalDouble.of(firstPrice), OptionalDouble.of(lastPrice),
                OptionalDouble.of(Math.min(firstPrice, lastPrice)),
                OptionalDouble.of(maxPrice), OptionalDouble.of(lastPrice - firstPrice),
                OptionalDouble.of(maxPrice - Math.min(firstPrice, lastPrice)),
                OptionalDouble.of(lastPrice - firstPrice));
    }
}
