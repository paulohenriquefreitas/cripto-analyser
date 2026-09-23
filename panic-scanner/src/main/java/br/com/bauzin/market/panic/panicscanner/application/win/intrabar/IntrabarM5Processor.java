package br.com.bauzin.market.panic.panicscanner.application.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

/** Single-owner Java processor. No IO, clock, source knowledge or trading rules. */
public final class IntrabarM5Processor {
    public static final int MAX_BARS = 1000;
    private BarSeries series;
    private SMAIndicator sma9;
    private SMAIndicator sma21;
    private String symbol;
    private long previousTimeMsc;
    private long analysisStart;
    private IntrabarCandleSnapshot current;

    public IntrabarM5Processor() { reset(); }

    /** Clears everything, including warm-up, symbol and time ordering. */
    public void reset() {
        series = new BaseBarSeriesBuilder().withName("canonical-M5").build();
        series.setMaximumBarCount(MAX_BARS);
        var close = new ClosePriceIndicator(series);
        sma9 = new SMAIndicator(close, 9);
        sma21 = new SMAIndicator(close, 21);
        symbol = null;
        previousTimeMsc = -1;
        analysisStart = 0;
        current = null;
    }

    /** Only on an empty processor; validates the entire input before mutation. */
    public void warmUp(String requestedSymbol, long start, List<IntrabarCandleSnapshot> closed) {
        if (symbol != null || !series.isEmpty()) throw new IllegalStateException("Reset before warm-up");
        String normalized = CanonicalPriceEvent.normalizeSymbol(requestedSymbol);
        long firstBucket = M5Bucket.start(start);
        List<IntrabarCandleSnapshot> input = List.copyOf(closed);
        long previous = -1;
        for (var candle : input) {
            if (!normalized.equals(candle.symbol()) || candle.bucketStartTimeMsc() <= previous
                    || candle.bucketStartTimeMsc() >= firstBucket)
                throw new IllegalArgumentException("Warm-up must be ordered, same-symbol, closed before analysis bucket");
            previous = candle.bucketStartTimeMsc();
        }
        symbol = normalized;
        analysisStart = start;
        for (var candle : input.subList(Math.max(0, input.size() - MAX_BARS), input.size())) addBar(candle);
    }

    /** Optional official partial-bar baseline for controlled shadow comparison only. */
    public void seedCurrentCandle(IntrabarCandleSnapshot candle) {
        Objects.requireNonNull(candle);
        if (symbol == null || current != null || previousTimeMsc >= 0)
            throw new IllegalStateException("Seed only immediately after warm-up");
        if (!symbol.equals(candle.symbol()) || candle.bucketStartTimeMsc() != M5Bucket.start(analysisStart))
            throw new IllegalArgumentException("Seed must match analysis bucket and symbol");
        addBar(candle);
        current = candle;
    }

    public IntrabarMarketState onEvent(CanonicalPriceEvent event) {
        Objects.requireNonNull(event);
        // All input-dependent rejections occur before any state mutation.
        if (symbol != null && !symbol.equals(event.symbol())) throw new IllegalArgumentException("Mixed symbols");
        if (event.timeMsc() < previousTimeMsc || event.timeMsc() < analysisStart)
            throw new IllegalArgumentException("Out-of-order or pre-analysis event");
        long bucket = M5Bucket.start(event.timeMsc());
        IntrabarCandleSnapshot completed = null;
        if (current == null || bucket > current.bucketStartTimeMsc()) {
            completed = current;
            addBar(new IntrabarCandleSnapshot(event.symbol(), bucket,
                    event.price(), event.price(), event.price(), event.price()));
        } else {
            series.getLastBar().addPrice(series.numFactory().numOf(event.price()));
        }
        symbol = event.symbol();
        previousTimeMsc = event.timeMsc();
        var bar = series.getLastBar();
        current = new IntrabarCandleSnapshot(symbol, bucket, bar.getOpenPrice().doubleValue(),
                bar.getHighPrice().doubleValue(), bar.getLowPrice().doubleValue(), bar.getClosePrice().doubleValue());
        int index = series.getEndIndex();
        return new IntrabarMarketState(symbol, event.timeMsc(), event.price(), current,
                series.getBarCount() < 9 ? null : sma9.getValue(index).doubleValue(),
                series.getBarCount() < 21 ? null : sma21.getValue(index).doubleValue(), completed);
    }

    private void addBar(IntrabarCandleSnapshot candle) {
        var n = series.numFactory();
        var begin = Instant.ofEpochMilli(candle.bucketStartTimeMsc());
        var period = Duration.ofMillis(M5Bucket.DURATION_MSC);
        series.addBar(new BaseBar(period, begin, begin.plus(period), n.numOf(candle.open()),
                n.numOf(candle.high()), n.numOf(candle.low()), n.numOf(candle.close()),
                n.zero(), n.zero(), 0L));
    }

    public IntrabarCandleSnapshot currentCandle() { return current; }
    public String symbol() { return symbol; }
    public long previousTimeMsc() { return previousTimeMsc; }
    public int retainedBarCount() { return series.getBarCount(); }
}
