package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import java.util.Objects;

/** Effort-versus-result measurements for the supported intrabar windows. */
public record FlowEffortResultSet(
        FlowEffortResult oneSecond,
        FlowEffortResult threeSeconds,
        FlowEffortResult fiveSeconds,
        FlowEffortResult tenSeconds) {

    public FlowEffortResultSet {
        Objects.requireNonNull(oneSecond, "oneSecond must not be null");
        Objects.requireNonNull(threeSeconds, "threeSeconds must not be null");
        Objects.requireNonNull(fiveSeconds, "fiveSeconds must not be null");
        Objects.requireNonNull(tenSeconds, "tenSeconds must not be null");
    }

    public static FlowEffortResultSet from(FlowSnapshotSet snapshots) {
        Objects.requireNonNull(snapshots, "snapshots must not be null");
        return new FlowEffortResultSet(
                FlowEffortResult.from(snapshots.oneSecond()),
                FlowEffortResult.from(snapshots.threeSeconds()),
                FlowEffortResult.from(snapshots.fiveSeconds()),
                FlowEffortResult.from(snapshots.tenSeconds()));
    }
}
