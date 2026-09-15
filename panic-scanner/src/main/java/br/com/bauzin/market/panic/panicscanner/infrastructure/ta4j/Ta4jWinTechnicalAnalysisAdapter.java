package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.application.win.WinTechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
public class Ta4jWinTechnicalAnalysisAdapter implements WinTechnicalAnalysisEngine {

    private static final int SHORT_PERIOD = 9;
    private static final int LONG_PERIOD = 21;
    private static final int RSI_PERIOD = 9;
    private static final int ATR_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int RECENT_PERIOD = 10;
    private static final int RELATIVE_VOLUME_PERIOD = 20;
    private static final int SLOPE_LOOKBACK = 5;

    @Override
    public int minimumRequiredCandles() {
        return LONG_PERIOD + 2;
    }

    @Override
    public WinTechnicalSnapshot analyze(String timeframe, List<WinCandle> candles) {
        if (candles.size() < minimumRequiredCandles()) {
            throw new IllegalArgumentException("At least " + minimumRequiredCandles() + " WIN candles are required");
        }
        List<WinCandle> ordered = candles.stream()
                .sorted(Comparator.comparing(WinCandle::timestamp))
                .toList();

        BarSeries series = toBarSeries(timeframe, ordered);
        int lastIndex = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma9 = new SMAIndicator(closePrice, SHORT_PERIOD);
        SMAIndicator sma21 = new SMAIndicator(closePrice, LONG_PERIOD);
        EMAIndicator ema9 = new EMAIndicator(closePrice, SHORT_PERIOD);
        EMAIndicator ema21 = new EMAIndicator(closePrice, LONG_PERIOD);
        RSIIndicator rsi9 = new RSIIndicator(closePrice, RSI_PERIOD);
        ATRIndicator atr14 = new ATRIndicator(series, ATR_PERIOD);
        ADXIndicator adx14 = new ADXIndicator(series, ADX_PERIOD);

        WinCandle last = ordered.get(ordered.size() - 1);
        BigDecimal lastSma9 = value(sma9.getValue(lastIndex));
        BigDecimal lastSma21 = value(sma21.getValue(lastIndex));
        BigDecimal lastEma9 = value(ema9.getValue(lastIndex));
        BigDecimal lastEma21 = value(ema21.getValue(lastIndex));
        BigDecimal lastRsi9 = value(rsi9.getValue(lastIndex));
        BigDecimal lastAtr14 = value(atr14.getValue(lastIndex));
        BigDecimal lastAdx14 = value(adx14.getValue(lastIndex));
        BigDecimal sessionHigh = highestHigh(ordered, ordered.size());
        BigDecimal sessionLow = lowestLow(ordered, ordered.size());
        BigDecimal recentHigh = highestHigh(ordered, RECENT_PERIOD);
        BigDecimal recentLow = lowestLow(ordered, RECENT_PERIOD);
        BigDecimal vwap = vwap(ordered);

        return new WinTechnicalSnapshot(
                lastSma9,
                lastSma21,
                lastEma9,
                lastEma21,
                lastRsi9,
                lastAtr14,
                lastAdx14,
                last.volume().setScale(4, RoundingMode.HALF_UP),
                relativeVolume(ordered),
                sessionHigh,
                sessionLow,
                recentHigh,
                recentLow,
                vwap,
                percent(last.close().subtract(lastEma9), lastEma9),
                percent(last.close().subtract(lastEma21), lastEma21),
                percent(last.close().subtract(vwap), vwap),
                sessionHigh.subtract(last.close()).setScale(4, RoundingMode.HALF_UP),
                last.close().subtract(sessionLow).setScale(4, RoundingMode.HALF_UP),
                candleBodyPercent(last),
                upperWickPercent(last),
                lowerWickPercent(last),
                consecutiveCandles(ordered, true),
                consecutiveCandles(ordered, false),
                slope(sma9, lastIndex),
                slope(sma21, lastIndex),
                last.close().subtract(recentHigh).setScale(4, RoundingMode.HALF_UP),
                last.close().subtract(recentLow).setScale(4, RoundingMode.HALF_UP),
                pullbackDepth(ordered),
                sessionHigh.subtract(sessionLow).setScale(4, RoundingMode.HALF_UP),
                breakoutDistance(last, recentHigh, recentLow),
                atrExtension(last.close(), lastEma21, lastAtr14),
                higherHigh(ordered),
                higherLow(ordered),
                lowerHigh(ordered),
                lowerLow(ordered));
    }

    private BarSeries toBarSeries(String timeframe, List<WinCandle> candles) {
        BarSeries series = new BaseBarSeriesBuilder().withName("win-" + timeframe).build();
        Duration duration = duration(timeframe);
        candles.forEach(candle -> series.addBar(new BaseBar(
                duration,
                candle.timestamp().toInstant().minus(duration),
                candle.timestamp().toInstant(),
                DecimalNum.valueOf(candle.open()),
                DecimalNum.valueOf(candle.high()),
                DecimalNum.valueOf(candle.low()),
                DecimalNum.valueOf(candle.close()),
                DecimalNum.valueOf(candle.volume()),
                DecimalNum.valueOf(candle.close().multiply(candle.volume())),
                0L)));
        return series;
    }

    private Duration duration(String timeframe) {
        if ("1m".equalsIgnoreCase(timeframe)) return Duration.ofMinutes(1);
        if ("5m".equalsIgnoreCase(timeframe)) return Duration.ofMinutes(5);
        return Duration.ofMinutes(1);
    }

    private BigDecimal value(Num num) {
        BigDecimal decimal = num.bigDecimalValue();
        return decimal == null ? zero() : decimal.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return zero();
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal highestHigh(List<WinCandle> candles, int period) {
        return candles.stream().skip(Math.max(0, candles.size() - period))
                .map(WinCandle::high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal lowestLow(List<WinCandle> candles, int period) {
        return candles.stream().skip(Math.max(0, candles.size() - period))
                .map(WinCandle::low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal relativeVolume(List<WinCandle> candles) {
        if (candles.size() <= RELATIVE_VOLUME_PERIOD) return zero();
        BigDecimal baseline = candles.stream()
                .skip(candles.size() - RELATIVE_VOLUME_PERIOD - 1L)
                .limit(RELATIVE_VOLUME_PERIOD)
                .map(WinCandle::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(RELATIVE_VOLUME_PERIOD), 4, RoundingMode.HALF_UP);
        if (baseline.signum() == 0) return zero();
        return candles.get(candles.size() - 1).volume().divide(baseline, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal vwap(List<WinCandle> candles) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (WinCandle candle : candles) {
            BigDecimal typical = candle.high().add(candle.low()).add(candle.close()).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            weighted = weighted.add(typical.multiply(candle.volume()));
            volume = volume.add(candle.volume());
        }
        return volume.signum() == 0 ? zero() : weighted.divide(volume, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal candleBodyPercent(WinCandle candle) {
        BigDecimal range = candle.high().subtract(candle.low()).abs();
        return range.signum() == 0 ? zero() : percent(candle.close().subtract(candle.open()).abs(), range);
    }

    private BigDecimal upperWickPercent(WinCandle candle) {
        BigDecimal range = candle.high().subtract(candle.low()).abs();
        BigDecimal topBody = candle.open().max(candle.close());
        return range.signum() == 0 ? zero() : percent(candle.high().subtract(topBody), range);
    }

    private BigDecimal lowerWickPercent(WinCandle candle) {
        BigDecimal range = candle.high().subtract(candle.low()).abs();
        BigDecimal bottomBody = candle.open().min(candle.close());
        return range.signum() == 0 ? zero() : percent(bottomBody.subtract(candle.low()), range);
    }

    private int consecutiveCandles(List<WinCandle> candles, boolean bullish) {
        int count = 0;
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (bullish ? candles.get(i).bullish() : candles.get(i).bearish()) count++;
            else break;
        }
        return count;
    }

    private BigDecimal slope(org.ta4j.core.Indicator<Num> indicator, int lastIndex) {
        if (lastIndex < SLOPE_LOOKBACK) return zero();
        BigDecimal current = value(indicator.getValue(lastIndex));
        BigDecimal past = value(indicator.getValue(lastIndex - SLOPE_LOOKBACK));
        return percent(current.subtract(past), past);
    }

    private BigDecimal pullbackDepth(List<WinCandle> candles) {
        BigDecimal recentHigh = highestHigh(candles, RECENT_PERIOD);
        BigDecimal recentLow = lowestLow(candles, RECENT_PERIOD);
        BigDecimal close = candles.get(candles.size() - 1).close();
        return close.subtract(recentLow).min(recentHigh.subtract(close)).abs().setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal breakoutDistance(WinCandle last, BigDecimal recentHigh, BigDecimal recentLow) {
        if (last.close().compareTo(recentHigh) > 0) return last.close().subtract(recentHigh).setScale(4, RoundingMode.HALF_UP);
        if (last.close().compareTo(recentLow) < 0) return recentLow.subtract(last.close()).setScale(4, RoundingMode.HALF_UP);
        return zero();
    }

    private BigDecimal atrExtension(BigDecimal close, BigDecimal ema21, BigDecimal atr14) {
        if (atr14 == null || atr14.signum() == 0) return zero();
        return close.subtract(ema21).abs().divide(atr14, 4, RoundingMode.HALF_UP);
    }

    private boolean higherHigh(List<WinCandle> candles) {
        return highestHigh(candles.subList(0, candles.size() - 1), RECENT_PERIOD)
                .compareTo(highestHigh(candles, RECENT_PERIOD)) < 0;
    }

    private boolean higherLow(List<WinCandle> candles) {
        return lowestLow(candles, RECENT_PERIOD).compareTo(lowestLow(candles.subList(0, candles.size() - 1), RECENT_PERIOD)) > 0;
    }

    private boolean lowerHigh(List<WinCandle> candles) {
        return highestHigh(candles, RECENT_PERIOD).compareTo(highestHigh(candles.subList(0, candles.size() - 1), RECENT_PERIOD)) < 0;
    }

    private boolean lowerLow(List<WinCandle> candles) {
        return lowestLow(candles, RECENT_PERIOD).compareTo(lowestLow(candles.subList(0, candles.size() - 1), RECENT_PERIOD)) < 0;
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
}
