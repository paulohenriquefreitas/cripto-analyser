package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;

/** Original epoch timestamps, no feed offset correction. */
public record CandleUpdate(IntrabarCandleSnapshot candle, int timeframeMinutes, boolean closed, long availableAtMsc) {
    public CandleUpdate {
        if (candle == null || (timeframeMinutes != 5 && timeframeMinutes != 15 && timeframeMinutes != 60)
                || candle.bucketStartTimeMsc() % (timeframeMinutes * 60_000L) != 0
                || availableAtMsc < candle.bucketStartTimeMsc()
                || (closed && availableAtMsc < candle.bucketStartTimeMsc() + timeframeMinutes * 60_000L))
            throw new IllegalArgumentException("Invalid candle availability/boundary");
    }
}
