package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import org.ta4j.core.*;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.time.*;
import java.util.*;

/** One validated UTC session. Immutable feature snapshots and separate future-only label state. */
public final class MlCandleDataset {
    public static final long M5 = 300_000L;
    public static final int CONTEXT = 20;
    private static final List<String> CONTEXT_FIELDS = List.of("return", "range_atr", "body_atr", "upper_wick_atr",
            "lower_wick_atr", "ema_delta_atr", "close_ema_atr");
    public record Feature(long bucket, List<String> values, List<String> contextValues, boolean usable) {
        public Feature { values = List.copyOf(values); contextValues = List.copyOf(contextValues); }
    }
    public record Example(Feature feature, List<String> labels) { public Example { labels = List.copyOf(labels); } }
    public record Session(List<Feature> candles, List<Example> examples, int warmupDiscarded, int horizonDiscarded) {
        public Session { candles = List.copyOf(candles); examples = List.copyOf(examples); }
    }
    private final String symbol;
    private final LocalDate date;
    private final ZoneId sessionZone;
    private final double thresholdAtr;
    private final BarSeries series = new BaseBarSeriesBuilder().withName("ml-closed-only").build();
    private final EMAIndicator ema = new EMAIndicator(new ClosePriceIndicator(series), 9);
    private final ATRIndicator atr = new ATRIndicator(series, 14);
    private final ArrayDeque<Feature> context = new ArrayDeque<>();
    private final List<Feature> features = new ArrayList<>();
    private final List<Example> examples = new ArrayList<>();
    private final List<Future> pending = new ArrayList<>();
    private IntrabarCandleSnapshot current;
    private long previousTime = -1, previousSequence = -1, previousClosed = -1;
    private int warmupDiscarded, horizonDiscarded;
    private boolean finished;

    public MlCandleDataset(String symbol, LocalDate date, ZoneId sessionZone, double thresholdAtr) {
        if (!Double.isFinite(thresholdAtr) || thresholdAtr <= 0) throw new IllegalArgumentException("Positive label ATR multiplier required");
        this.symbol = symbol; this.date = date; this.sessionZone = sessionZone; this.thresholdAtr = thresholdAtr;
    }
    public void warmUp(Mt5HistoricalPriceSource.Header header) {
        if (!series.isEmpty() || current != null) throw new IllegalStateException("Warm up once before ticks");
        long previous = -1;
        for (var c : header.warmup()) {
            var candle = Mt5CanonicalPriceMapper.candle(symbol, c);
            if (candle.bucketStartTimeMsc() <= previous || candle.bucketStartTimeMsc() + M5 > header.startMsc())
                throw new IllegalArgumentException("Warmup must be closed and strictly ordered before session");
            addBar(candle); previous = candle.bucketStartTimeMsc();
            // Force incremental indicator calculation; later bars never revise old values.
            ema.getValue(series.getEndIndex()); atr.getValue(series.getEndIndex());
        }
    }
    public void onLast(HistoricalCanonicalPriceEvent tick) {
        if (finished) throw new IllegalStateException("Finished");
        var e = tick.priceEvent();
        if (!symbol.equals(e.symbol()) || e.timeMsc() < previousTime || tick.sequence() <= previousSequence
                || !Instant.ofEpochMilli(e.timeMsc()).atZone(ZoneOffset.UTC).toLocalDate().equals(date))
            throw new IllegalArgumentException("Ordered same-session LASTs required");
        previousTime = e.timeMsc(); previousSequence = tick.sequence();
        long bucket = M5Bucket.start(e.timeMsc());
        if (current == null || current.bucketStartTimeMsc() != bucket) {
            if (current != null) close(current);
            current = new IntrabarCandleSnapshot(symbol, bucket, e.price(), e.price(), e.price(), e.price());
        } else current = new IntrabarCandleSnapshot(symbol, bucket, current.open(), Math.max(current.high(), e.price()),
                Math.min(current.low(), e.price()), e.price());
        for (var f : pending) f.observe(e.timeMsc(), e.price());
    }
    private void addBar(IntrabarCandleSnapshot c) {
        var n = series.numFactory(); var begin = Instant.ofEpochMilli(c.bucketStartTimeMsc());
        series.addBar(new BaseBar(Duration.ofMinutes(5), begin, begin.plusSeconds(300), n.numOf(c.open()),
                n.numOf(c.high()), n.numOf(c.low()), n.numOf(c.close()), n.zero(), n.zero(), 0L));
    }
    private void close(IntrabarCandleSnapshot c) {
        // A gap invalidates pending labels and the contiguous context. Never fill missing bars.
        if (previousClosed != -1 && c.bucketStartTimeMsc() != previousClosed + M5) {
            horizonDiscarded += pending.size(); pending.clear(); context.clear();
        }
        previousClosed = c.bucketStartTimeMsc();
        var it = pending.iterator();
        while (it.hasNext()) {
            var f = it.next();
            if (c.bucketStartTimeMsc() != f.feature.bucket() + (++f.closedFuture) * M5) {
                horizonDiscarded++; it.remove();
            } else if (f.closedFuture == 3) {
                examples.add(f.finish(c.close())); it.remove();
            }
        }
        double previousEma = series.isEmpty() ? Double.NaN : ema.getValue(series.getEndIndex()).doubleValue();
        double previousClose = series.isEmpty() ? Double.NaN : series.getLastBar().getClosePrice().doubleValue();
        addBar(c); int index = series.getEndIndex();
        double emaValue = ema.getValue(index).doubleValue(), atrValue = atr.getValue(index).doubleValue();
        double range = c.high() - c.low(), body = c.close() - c.open();
        double upper = c.high() - Math.max(c.open(), c.close()), lower = Math.min(c.open(), c.close()) - c.low();
        double ret = c.close() / previousClose - 1, delta = emaValue - previousEma, distance = c.close() - emaValue;
        boolean indicatorReady = index >= Math.max(14, atr.getCountOfUnstableBars()) && Double.isFinite(atrValue) && atrValue > 0
                && Double.isFinite(ret) && Double.isFinite(delta);
        var clock = Instant.ofEpochMilli(c.bucketStartTimeMsc()).atZone(sessionZone);
        var values = new ArrayList<String>(List.of(symbol, date.toString(), split(date), Long.toString(c.bucketStartTimeMsc()),
                Instant.ofEpochMilli(c.bucketStartTimeMsc()).toString(), Instant.ofEpochMilli(c.bucketStartTimeMsc() + M5).toString(),
                sessionZone.getId(), Integer.toString(clock.getHour()), Integer.toString(clock.getMinute()),
                number(c.open()), number(c.high()), number(c.low()), number(c.close()), number(range), number(Math.abs(body)),
                number(body), number(upper), number(lower), number(ret), number(emaValue), number(delta), number(atrValue), number(distance),
                number(range / atrValue), number(body / atrValue), number(upper / atrValue), number(lower / atrValue),
                number(delta / atrValue), number(distance / atrValue), number(atrValue / c.close())));
        List<String> normalized = List.of(number(ret), number(range / atrValue), number(body / atrValue), number(upper / atrValue),
                number(lower / atrValue), number(delta / atrValue), number(distance / atrValue));
        boolean usable = indicatorReady && context.size() == CONTEXT && context.stream().allMatch(f -> f.contextValues().stream().noneMatch(String::isEmpty));
        if (context.size() == CONTEXT) for (var f : context) values.addAll(f.contextValues());
        else for (int i = 0; i < CONTEXT * CONTEXT_FIELDS.size(); i++) values.add("");
        Feature feature = new Feature(c.bucketStartTimeMsc(), values, normalized, usable);
        features.add(feature);
        if (usable) pending.add(new Future(feature, c.close(), atrValue, thresholdAtr)); else warmupDiscarded++;
        context.addLast(feature); if (context.size() > CONTEXT) context.removeFirst();
    }
    /** Only after COMPLETE file validation: EOF proves the last observed bucket is closed. No invented LAST. */
    public Session finish() {
        if (!finished) {
            if (current != null) close(current);
            horizonDiscarded += pending.size(); pending.clear(); finished = true;
        }
        return new Session(features, examples, warmupDiscarded, horizonDiscarded);
    }
    private static String number(double n) { return Double.isFinite(n) ? Double.toString(n) : ""; }
    private static String split(LocalDate date) {
        return switch (date.getMonthValue()) { case 4,5,6,7 -> "DISCOVERY"; case 8 -> "VALIDATION"; case 9 -> "TEST"; default -> "UNASSIGNED"; };
    }
    public static List<String> featureColumns() {
        var columns = new ArrayList<>(List.of("symbol", "session_date_utc", "temporal_split", "bucket_start_msc", "timestamp_utc",
                "feature_available_at_utc", "session_timezone", "session_hour", "session_minute", "open", "high", "low", "close",
                "range", "body_abs", "body_signed", "upper_wick", "lower_wick", "close_return", "ema9", "ema9_delta", "atr14",
                "close_ema9", "range_atr", "body_atr", "upper_wick_atr", "lower_wick_atr", "ema9_delta_atr", "close_ema9_atr", "atr_price"));
        for (int lag = CONTEXT; lag >= 1; lag--) for (var field : CONTEXT_FIELDS) columns.add("ctx_lag" + lag + "_" + field);
        return List.copyOf(columns);
    }
    public static List<String> labelColumns() { return List.of("label_horizon_end_utc", "label_up_excursion", "label_down_excursion",
            "label_return_15m", "label_up_excursion_atr", "label_down_excursion_atr", "label_return_atr", "label_direction", "label_first_touch"); }
    private static final class Future {
        final Feature feature; final double anchorClose, anchorAtr, threshold;
        double up, down; int closedFuture; String first = "NONE";
        Future(Feature feature, double close, double atr, double multiplier) {
            this.feature = feature; anchorClose = close; anchorAtr = atr; threshold = multiplier * atr;
        }
        void observe(long time, double price) {
            if (time < feature.bucket() + M5 || time >= feature.bucket() + 4 * M5) return;
            up = Math.max(up, price - anchorClose); down = Math.max(down, anchorClose - price);
            if (first.equals("NONE")) {
                if (price - anchorClose >= threshold) first = "UP_FIRST";
                else if (anchorClose - price >= threshold) first = "DOWN_FIRST";
            }
        }
        Example finish(double close) {
            double movement = close - anchorClose;
            String direction = movement >= threshold ? "UP" : movement <= -threshold ? "DOWN" : "FLAT";
            return new Example(feature, List.of(Instant.ofEpochMilli(feature.bucket() + 4 * M5).toString(), number(up), number(down),
                    number(close / anchorClose - 1), number(up / anchorAtr), number(down / anchorAtr), number(movement / anchorAtr), direction, first));
        }
    }
}
