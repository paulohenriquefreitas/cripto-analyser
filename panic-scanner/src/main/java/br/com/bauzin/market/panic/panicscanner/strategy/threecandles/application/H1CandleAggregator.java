package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Native feed hour boundaries. No clock shifts, synthetic bars or cross-session OHLC.
 * A sparse/session-tail hour closes only once a subsequent hour is observed.
 * A truncated leading hour is discarded, since its open/high/low are unknown. */
public final class H1CandleAggregator {
    private static final long HOUR = 3_600_000L;
    private IntrabarCandleSnapshot pending;
    private long bucket = Long.MIN_VALUE;
    private boolean published;

    public Optional<CandleUpdate> accept(CandleUpdate update) {
        if (update.timeframeMinutes() != 5) throw new IllegalArgumentException("H1 requires M5 source");
        var candle = update.candle();
        long hour = candle.bucketStartTimeMsc() / HOUR * HOUR;
        CandleUpdate result = null;
        if (hour > bucket) {
            boolean initial = bucket == Long.MIN_VALUE;
            if (pending != null && !published)
                result = new CandleUpdate(pending, 60, true, Math.max(bucket + HOUR, update.availableAtMsc()));
            bucket = hour; pending = null; published = false;
            // Only the initial slice can be truncated. Later hours may open after :00
            // when the authoritative source contains no trades in its first M5 slot.
            if (initial && candle.bucketStartTimeMsc() != hour) published = true;
        }
        if (!update.closed() || published) return Optional.ofNullable(result);
        pending = pending == null ? new IntrabarCandleSnapshot(candle.symbol(), hour,
                candle.open(), candle.high(), candle.low(), candle.close())
                : new IntrabarCandleSnapshot(candle.symbol(), hour, pending.open(),
                    Math.max(pending.high(), candle.high()), Math.min(pending.low(), candle.low()), candle.close());
        if (candle.bucketStartTimeMsc() + 300_000L == hour + HOUR) {
            result = new CandleUpdate(pending, 60, true, update.availableAtMsc());
            published = true;
        }
        return Optional.ofNullable(result);
    }
}
