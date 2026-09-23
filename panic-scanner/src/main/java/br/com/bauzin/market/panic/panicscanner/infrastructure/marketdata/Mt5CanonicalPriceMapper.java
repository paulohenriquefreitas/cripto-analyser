package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import java.util.Optional;

/** Source-specific policy lives here, never in the candle processor. */
public final class Mt5CanonicalPriceMapper {
    public static final int TICK_FLAG_LAST = 8;
    private Mt5CanonicalPriceMapper() {}

    public static CanonicalPriceEvent liveSnapshot(String symbol, Mt5Tick tick) {
        if (Math.floorDiv(tick.timeMsc(), 1000) != tick.time())
            throw new IllegalArgumentException("Inconsistent MT5 time/time_msc");
        return new CanonicalPriceEvent(symbol, tick.timeMsc(), tick.last());
    }

    public static Optional<CanonicalPriceEvent> historical(String symbol, long timeMsc, double last, int flags) {
        if ((flags & TICK_FLAG_LAST) == 0) return Optional.empty();
        return Optional.of(new CanonicalPriceEvent(symbol, timeMsc, last));
    }

    public static IntrabarCandleSnapshot candle(String symbol, Mt5Candle candle) {
        return new IntrabarCandleSnapshot(symbol, Math.multiplyExact(candle.time(), 1000),
                candle.open(), candle.high(), candle.low(), candle.close());
    }
}
