package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

@Component
public class Ta4jMt5Sma9Adapter {
    private final br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.LegacyShadowCapture shadowCapture =
            new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.LegacyShadowCapture();
    private long syncGeneration;
    private long syncReplayThrough = -1;
    private BarSeries series;
    private SMAIndicator sma;
    private SMAIndicator sma21;
    private Mt5Tick latestTick;
    private long currentTime;

    /** Build/warm outside the tick lock. Called by the serialized REST use case. */
    public List<Mt5ChartCandle> synchronize(List<Mt5Candle> input) {
        List<Mt5Candle> candles = input.subList(Math.max(0, input.size() - 1000), input.size());
        BarSeries next = Mt5BarSeriesConverter.convert(candles);
        var close = new ClosePriceIndicator(next);
        SMAIndicator indicator = new SMAIndicator(close, 9);
        SMAIndicator indicator21 = new SMAIndicator(close, 21);
        var vwap = new Mt5SessionVwap(next);
        List<Mt5ChartCandle> result = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            // Require a full window, although TA4J can compute partial averages.
            Double value = i < 8 ? null : indicator.getValue(i).doubleValue();
            result.add(new Mt5ChartCandle(candles.get(i), value,
                    i < 20 ? null : indicator21.getValue(i).doubleValue(),
                    vwap.value(i), vwap.session(i).toString()));
        }
        synchronized (this) {
            if (!candles.isEmpty() && (series == null || candles.getLast().time() >= currentTime)) {
                series = next;
                sma = indicator;
                sma21 = indicator21;
                currentTime = candles.getLast().time();
                syncGeneration++;
                syncReplayThrough = latestTick == null ? -1 : latestTick.timeMsc();
                if (latestTick != null) apply(latestTick);
            }
        }
        return List.copyOf(result);
    }

    /** O(9 + 21) bounded TA4J work; no IO, no new bars and no history rebuild per tick. */
    public synchronized Current onTick(Mt5Tick tick) {
        if (!Double.isFinite(tick.last()) || tick.last() <= 0 || tick.timeMsc() < 0
                || tick.timeMsc() > Long.MAX_VALUE - M5Bucket.DURATION_MSC
                || Math.floorDiv(tick.timeMsc(), 1000) != tick.time()
                || (latestTick != null && tick.timeMsc() < latestTick.timeMsc())) return null;
        latestTick = tick;
        Current result = apply(tick);
        if (shadowCapture.enabled()) {
            // Capture under the SAME adapter lock as the update. No IO, callback,
            // consumer code, or synchronization of the shadow engine on this path.
            shadowCapture.offer(new br.com.bauzin.market.panic.panicscanner.application.win.intrabar.ShadowObservation(
                    "WINV26", tick.timeMsc(), tick.last(), candleSnapshot("WINV26"),
                    result == null ? null : result.value(), result == null ? null : result.sma21(),
                    tick.timeMsc() > syncReplayThrough, syncGeneration));
        }
        return result;
    }

    private Current apply(Mt5Tick tick) {
        if (series == null || series.isEmpty()
                || M5Bucket.start(tick.timeMsc()) != M5Bucket.start(Math.multiplyExact(currentTime, 1000))) return null;
        // addPrice preserves open/volume and updates only the mutable last bar.
        // TA4J 0.22.6 invalidates last-bar indicator caches when close changes.
        series.getLastBar().addPrice(series.numFactory().numOf(tick.last()));
        return series.getBarCount() < 9 ? null : new Current(currentTime, sma.getValue(series.getEndIndex()).doubleValue(),
                series.getBarCount() < 21 ? null : sma21.getValue(series.getEndIndex()).doubleValue());
    }

    public record Current(long time, double value, Double sma21) {}

    public br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.LegacyShadowCapture shadowCapture() {
        return shadowCapture;
    }

    /** Read-only diagnostic view of the actual legacy bar, never a second OHLC calculation. */
    public synchronized IntrabarCandleSnapshot candleSnapshot(String symbol) {
        if (series == null || series.isEmpty()) return null;
        var bar = series.getLastBar();
        return new IntrabarCandleSnapshot(symbol, bar.getBeginTime().toEpochMilli(),
                bar.getOpenPrice().doubleValue(), bar.getHighPrice().doubleValue(),
                bar.getLowPrice().doubleValue(), bar.getClosePrice().doubleValue());
    }
}
