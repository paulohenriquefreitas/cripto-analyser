package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;

/** Factual effort-versus-result measurements for one flow snapshot. */
public record FlowEffortResult(
        Duration window,
        double knownVolume,
        double knownDelta,
        OptionalDouble directionalImbalance,
        double knownEffortRate,
        double directionalEffortRate,
        double totalActivityRate,
        OptionalDouble ambiguousShare,
        OptionalDouble directionalCoverage,
        OptionalDouble priceResult,
        OptionalDouble priceVelocity,
        OptionalDouble priceRange,
        OptionalDouble alignedResult,
        OptionalDouble efficiency,
        OptionalDouble opposingPressure) {

    public FlowEffortResult {
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(directionalImbalance, "directionalImbalance must not be null");
        Objects.requireNonNull(ambiguousShare, "ambiguousShare must not be null");
        Objects.requireNonNull(directionalCoverage, "directionalCoverage must not be null");
        Objects.requireNonNull(priceResult, "priceResult must not be null");
        Objects.requireNonNull(priceVelocity, "priceVelocity must not be null");
        Objects.requireNonNull(priceRange, "priceRange must not be null");
        Objects.requireNonNull(alignedResult, "alignedResult must not be null");
        Objects.requireNonNull(efficiency, "efficiency must not be null");
        Objects.requireNonNull(opposingPressure, "opposingPressure must not be null");
    }

    public static FlowEffortResult from(FlowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        double seconds = snapshot.window().toNanos() / 1_000_000_000.0;
        double knownVolume = snapshot.knownVolume();
        double knownDelta = snapshot.knownDelta();
        double totalVolume = snapshot.totalVolume();

        OptionalDouble directionalImbalance = knownVolume == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(knownDelta / knownVolume);
        OptionalDouble ambiguousShare = totalVolume == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(snapshot.ambiguousVolume() / totalVolume);
        OptionalDouble directionalCoverage = totalVolume == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(knownVolume / totalVolume);

        OptionalDouble priceResult = snapshot.priceChange();
        OptionalDouble alignedResult = OptionalDouble.empty();
        OptionalDouble efficiency = OptionalDouble.empty();
        OptionalDouble opposingPressure = OptionalDouble.empty();
        if (knownDelta != 0 && priceResult.isPresent()) {
            double aligned = Math.signum(knownDelta) * priceResult.getAsDouble();
            alignedResult = OptionalDouble.of(aligned);
            efficiency = OptionalDouble.of(Math.abs(priceResult.getAsDouble()) / Math.abs(knownDelta));
            opposingPressure = OptionalDouble.of(Math.max(0, -aligned));
        }

        return new FlowEffortResult(
                snapshot.window(),
                knownVolume,
                knownDelta,
                directionalImbalance,
                knownVolume / seconds,
                Math.abs(knownDelta) / seconds,
                snapshot.contractsPerSecond(),
                ambiguousShare,
                directionalCoverage,
                priceResult,
                snapshot.priceVelocity(),
                snapshot.priceRange(),
                alignedResult,
                efficiency,
                opposingPressure);
    }
}
