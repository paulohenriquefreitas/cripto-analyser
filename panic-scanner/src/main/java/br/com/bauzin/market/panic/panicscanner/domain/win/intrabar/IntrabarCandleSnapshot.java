package br.com.bauzin.market.panic.panicscanner.domain.win.intrabar;

/** No volume: TA4J storage volume is unused by this price-only pipeline. */
public record IntrabarCandleSnapshot(String symbol, long bucketStartTimeMsc,
                                    double open, double high, double low, double close) {
    public IntrabarCandleSnapshot {
        symbol = CanonicalPriceEvent.normalizeSymbol(symbol);
        if (M5Bucket.start(bucketStartTimeMsc) != bucketStartTimeMsc)
            throw new IllegalArgumentException("Candle must start at an M5 boundary");
        CanonicalPriceEvent.requirePrice(open);
        CanonicalPriceEvent.requirePrice(high);
        CanonicalPriceEvent.requirePrice(low);
        CanonicalPriceEvent.requirePrice(close);
        if (low > Math.min(open, close) || high < Math.max(open, close) || low > high)
            throw new IllegalArgumentException("Invalid OHLC envelope");
    }
}
