package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;

public record FlowSnapshot(
        Duration window,
        long fromTimeMsc,
        long toTimeMsc,
        long tradeCount,
        double totalVolume,
        double buyVolume,
        double sellVolume,
        double ambiguousVolume,
        double knownVolume,
        double knownDelta,
        double buyShare,
        double sellShare,
        double tradesPerSecond,
        double contractsPerSecond,
        OptionalDouble firstPrice,
        OptionalDouble lastPrice,
        OptionalDouble minPrice,
        OptionalDouble maxPrice,
        OptionalDouble priceChange,
        OptionalDouble priceRange,
        OptionalDouble priceVelocity) {

    public FlowSnapshot {
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(firstPrice, "firstPrice must not be null");
        Objects.requireNonNull(lastPrice, "lastPrice must not be null");
        Objects.requireNonNull(minPrice, "minPrice must not be null");
        Objects.requireNonNull(maxPrice, "maxPrice must not be null");
        Objects.requireNonNull(priceChange, "priceChange must not be null");
        Objects.requireNonNull(priceRange, "priceRange must not be null");
        Objects.requireNonNull(priceVelocity, "priceVelocity must not be null");
    }

    public static FlowSnapshot empty(Duration window) {
        OptionalDouble empty = OptionalDouble.empty();
        return new FlowSnapshot(window, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, empty, empty, empty, empty, empty, empty, empty);
    }
}
