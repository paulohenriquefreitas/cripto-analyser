package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Mt5SessionVwap;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5CanonicalPriceMapper;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;

final class CausalSessionVwap {
    private final BarSeries series = new BaseBarSeriesBuilder().withName("replay-M5-vwap").build();
    private final Mt5SessionVwap vwap = new Mt5SessionVwap(series);
    private long currentBucket = Long.MIN_VALUE;
    private double currentVolume;

    CausalSessionVwap() {
        series.setMaximumBarCount(1000);
    }

    void warmUp(String symbol, java.util.List<br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle> candles) {
        for (var candle : candles) {
            addBar(candle.time() * 1000, candle.open(), candle.high(), candle.low(),
                    candle.close(), candle.realVolume());
        }
    }

    Double update(long timeMsc, double price, double volumeReal) {
        long bucket = Math.floorDiv(timeMsc, 300_000L) * 300_000L;
        if (currentBucket != bucket) {
            addBar(bucket, price, price, price, price, 0);
            currentBucket = bucket;
            currentVolume = 0;
        } else {
            var bar = series.getLastBar();
            bar.addPrice(series.numFactory().numOf(price));
        }
        if (volumeReal > 0) {
            currentVolume += volumeReal;
            series.getLastBar().addTrade(series.numFactory().numOf(volumeReal),
                    series.numFactory().numOf(price));
        }
        return vwap.value(series.getEndIndex());
    }

    double currentVolume() {
        return currentVolume;
    }

    static boolean isEligibleVolume(HistoricalCanonicalPriceEvent event) {
        return (event.flags() & Mt5CanonicalPriceMapper.TICK_FLAG_LAST) != 0
                && event.volumeReal() > 0;
    }

    private void addBar(long bucket, double open, double high, double low, double close, double volume) {
        var n = series.numFactory();
        Instant begin = Instant.ofEpochMilli(bucket);
        series.addBar(new BaseBar(Duration.ofMinutes(5), begin, begin.plus(Duration.ofMinutes(5)),
                n.numOf(open), n.numOf(high), n.numOf(low), n.numOf(close),
                n.numOf(volume), n.zero(), 0L));
    }
}
