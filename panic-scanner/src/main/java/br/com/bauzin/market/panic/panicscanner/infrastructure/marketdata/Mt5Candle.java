package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

/** MT5 candle with the original Unix timestamp in seconds. */
public record Mt5Candle(long time, double open, double high, double low, double close,
                        long tickVolume, long realVolume) {
}
