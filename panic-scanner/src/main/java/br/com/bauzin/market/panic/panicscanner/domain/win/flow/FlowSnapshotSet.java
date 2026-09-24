package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import java.util.Objects;

/** The intrabar flow views associated with one event-time instant. */
public record FlowSnapshotSet(
        FlowSnapshot oneSecond,
        FlowSnapshot threeSeconds,
        FlowSnapshot fiveSeconds,
        FlowSnapshot tenSeconds,
        long maxTradeTimeMscUsed) {

    public FlowSnapshotSet {
        Objects.requireNonNull(oneSecond, "oneSecond must not be null");
        Objects.requireNonNull(threeSeconds, "threeSeconds must not be null");
        Objects.requireNonNull(fiveSeconds, "fiveSeconds must not be null");
        Objects.requireNonNull(tenSeconds, "tenSeconds must not be null");
        if (maxTradeTimeMscUsed < -1) {
            throw new IllegalArgumentException("maxTradeTimeMscUsed must be -1 or non-negative");
        }
    }
}
