package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;

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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Component
public class Ta4jTechnicalAnalysisAdapter implements TechnicalAnalysisEngine {

    private static final int SMA_SHORT_PERIOD = 9;
    private static final int SMA_LONG_PERIOD = 21;
    private static final int EMA_SHORT_PERIOD = 9;
    private static final int EMA_LONG_PERIOD = 21;
    private static final int RSI_PERIOD = 9;
    private static final int ATR_PERIOD = 14;
    private static final int ADX_PERIOD = 14;
    private static final int AVERAGE_FINANCIAL_VOLUME_PERIOD = 20;
    private static final int AVERAGE_VOLUME_PERIOD = 20;
    private static final int RETURN_SHORT_PERIOD = 5;
    private static final int RETURN_LONG_PERIOD = 20;
    private static final int HIGH_LOW_PERIOD = 20;
    private static final int HIGH_LOW_SHORT_PERIOD = 5;
    private static final int HIGH_LOW_MEDIUM_PERIOD = 10;
    private static final int SLOPE_LOOKBACK = 5;
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");

    @Override
    public int minimumRequiredCandles() {
        return SMA_LONG_PERIOD;
    }

    @Override
    public TechnicalAnalysis analyze(String ticker, String timeframe, List<Candle> candles) {
        List<Candle> closedCandles = candles.stream()
                .filter(candle -> candle.status() == CandleStatus.CLOSED)
                .toList();

        if (closedCandles.size() < minimumRequiredCandles()) {
            throw new IllegalArgumentException("At least " + minimumRequiredCandles() + " candles are required");
        }

        List<Candle> orderedCandles = closedCandles.stream()
                .sorted(Comparator.comparing(Candle::date))
                .toList();

        BarSeries series = toBarSeries(orderedCandles);
        int lastIndex = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma9 = new SMAIndicator(closePrice, SMA_SHORT_PERIOD);
        SMAIndicator sma21 = new SMAIndicator(closePrice, SMA_LONG_PERIOD);
        EMAIndicator ema9 = new EMAIndicator(closePrice, EMA_SHORT_PERIOD);
        EMAIndicator ema21 = new EMAIndicator(closePrice, EMA_LONG_PERIOD);
        RSIIndicator rsi9 = new RSIIndicator(closePrice, RSI_PERIOD);
        ATRIndicator atr14 = new ATRIndicator(series, ATR_PERIOD);
        ADXIndicator adx14 = new ADXIndicator(series, ADX_PERIOD);

        BigDecimal lastSma9 = valueOrZero(sma9.getValue(lastIndex));
        BigDecimal lastSma21 = valueOrZero(sma21.getValue(lastIndex));
        BigDecimal lastEma9 = valueOrZero(ema9.getValue(lastIndex));
        BigDecimal lastEma21 = valueOrZero(ema21.getValue(lastIndex));
        BigDecimal lastRsi9 = valueOrZero(rsi9.getValue(lastIndex));
        BigDecimal lastAtr14 = valueOrZero(atr14.getValue(lastIndex));
        BigDecimal lastAdx14 = valueOrZero(adx14.getValue(lastIndex));
        BigDecimal averageVolume20 = averageVolume20(orderedCandles);
        BigDecimal averageFinancialVolume20 = averageFinancialVolume20(orderedCandles);
        Candle lastCandle = orderedCandles.get(orderedCandles.size() - 1);
        BigDecimal relativeVolume20 = relativeVolume20(orderedCandles);
        BigDecimal distanceFromSma21Percent = percent(lastCandle.close().subtract(lastSma21), lastSma21);
        BigDecimal return5DaysPercent = returnPercent(orderedCandles, RETURN_SHORT_PERIOD);
        BigDecimal return20DaysPercent = returnPercent(orderedCandles, RETURN_LONG_PERIOD);
        BigDecimal distanceFromEma9Percent = percent(lastCandle.close().subtract(lastEma9), lastEma9);
        BigDecimal distanceFromEma21Percent = percent(lastCandle.close().subtract(lastEma21), lastEma21);
        BigDecimal highestHigh5 = highestHigh(orderedCandles, HIGH_LOW_SHORT_PERIOD);
        BigDecimal highestHigh10 = highestHigh(orderedCandles, HIGH_LOW_MEDIUM_PERIOD);
        BigDecimal highestHigh20 = highestHigh(orderedCandles, HIGH_LOW_PERIOD);
        BigDecimal lowestLow5 = lowestLow(orderedCandles, HIGH_LOW_SHORT_PERIOD);
        BigDecimal lowestLow10 = lowestLow(orderedCandles, HIGH_LOW_MEDIUM_PERIOD);
        BigDecimal lowestLow20 = lowestLow(orderedCandles, HIGH_LOW_PERIOD);
        BigDecimal recentHigh = highestHigh10;
        java.time.LocalDate recentHighDate = recentHighDate(orderedCandles, HIGH_LOW_MEDIUM_PERIOD, recentHigh);
        BigDecimal recentLow = lowestLow10;
        java.time.LocalDate recentLowDate = recentLowDate(orderedCandles, HIGH_LOW_MEDIUM_PERIOD, recentLow);
        Integer candlesSinceRecentHigh = candlesSince(orderedCandles, recentHighDate);
        BigDecimal pullbackDepthPercent = percent(recentHigh.subtract(lastCandle.close()), recentHigh);
        Candle previousCandle = orderedCandles.size() > 1 ? orderedCandles.get(orderedCandles.size() - 2) : lastCandle;
        BigDecimal movingAverageSlope9 = slope(sma9, lastIndex, SLOPE_LOOKBACK);
        BigDecimal movingAverageSlope21 = slope(sma21, lastIndex, SLOPE_LOOKBACK);
        BigDecimal consolidationWidthPercent = percent(highestHigh20.subtract(lowestLow20), lowestLow20);
        BigDecimal bullishVolumeAverage = directionalVolumeAverage(orderedCandles, true);
        BigDecimal bearishVolumeAverage = directionalVolumeAverage(orderedCandles, false);

        return new TechnicalAnalysis(
                ticker,
                lastCandle.date(),
                timeframe,
                lastCandle.close(),
                lastSma9,
                lastSma21,
                lastEma9,
                lastEma21,
                lastRsi9,
                lastAtr14,
                lastAdx14,
                averageVolume20,
                averageFinancialVolume20,
                relativeVolume20,
                distanceFromSma21Percent,
                return5DaysPercent,
                return20DaysPercent,
                distanceFromEma9Percent,
                distanceFromEma21Percent,
                highestHigh5,
                highestHigh10,
                highestHigh20,
                lowestLow5,
                lowestLow10,
                lowestLow20,
                recentHigh,
                recentHighDate,
                recentLow,
                recentLowDate,
                candlesSinceRecentHigh,
                pullbackDepthPercent,
                previousCandle.high(),
                previousCandle.low(),
                previousCandle.close(),
                movingAverageSlope9,
                movingAverageSlope21,
                consolidationWidthPercent,
                bullishVolumeAverage,
                bearishVolumeAverage,
                lastCandle.volume(),
                previousCandle.volume(),
                trend(lastSma9, lastSma21));
    }

    BarSeries toBarSeries(List<Candle> candles) {
        if (candles.stream().anyMatch(candle -> candle.status() != CandleStatus.CLOSED)) {
            throw new IllegalArgumentException("Daily BarSeries can only be built from closed candles");
        }
        BarSeries series = new BaseBarSeriesBuilder().withName("daily-candles").build();
        candles.stream()
                .sorted(Comparator.comparing(Candle::date))
                .map(this::toBar)
                .forEach(series::addBar);
        return series;
    }

    private BaseBar toBar(Candle candle) {
        var endTime = candle.date().atTime(LocalTime.MAX).atZone(MARKET_ZONE).toInstant();
        return new BaseBar(
                Duration.ofDays(1),
                endTime.minus(Duration.ofDays(1)),
                endTime,
                DecimalNum.valueOf(candle.open()),
                DecimalNum.valueOf(candle.high()),
                DecimalNum.valueOf(candle.low()),
                DecimalNum.valueOf(candle.close()),
                DecimalNum.valueOf(candle.volume()),
                DecimalNum.valueOf(candle.financialVolume()),
                0L);
    }

    private BigDecimal averageFinancialVolume20(List<Candle> orderedCandles) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - AVERAGE_FINANCIAL_VOLUME_PERIOD))
                .map(Candle::financialVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.min(AVERAGE_FINANCIAL_VOLUME_PERIOD, orderedCandles.size())), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal valueOrZero(Num value) {
        BigDecimal decimal = value.bigDecimalValue();
        if (decimal == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return decimal.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume20(List<Candle> orderedCandles) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - AVERAGE_VOLUME_PERIOD))
                .map(Candle::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.min(AVERAGE_VOLUME_PERIOD, orderedCandles.size())), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal relativeVolume20(List<Candle> orderedCandles) {
        if (orderedCandles.size() <= AVERAGE_VOLUME_PERIOD) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal baseline = orderedCandles.stream()
                .skip(orderedCandles.size() - AVERAGE_VOLUME_PERIOD - 1L)
                .limit(AVERAGE_VOLUME_PERIOD)
                .map(Candle::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(AVERAGE_VOLUME_PERIOD), 4, RoundingMode.HALF_UP);
        if (baseline.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return orderedCandles.get(orderedCandles.size() - 1).volume().divide(baseline, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal returnPercent(List<Candle> orderedCandles, int period) {
        if (orderedCandles.size() <= period) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal currentClose = orderedCandles.get(orderedCandles.size() - 1).close();
        BigDecimal previousClose = orderedCandles.get(orderedCandles.size() - period - 1).close();
        return percent(currentClose.subtract(previousClose), previousClose);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal highestHigh(List<Candle> orderedCandles, int period) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - period))
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal lowestLow(List<Candle> orderedCandles, int period) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - period))
                .map(Candle::low)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private java.time.LocalDate recentHighDate(List<Candle> orderedCandles, int period, BigDecimal recentHigh) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - period))
                .filter(candle -> candle.high().compareTo(recentHigh) == 0)
                .reduce((first, second) -> second)
                .map(Candle::date)
                .orElse(null);
    }

    private java.time.LocalDate recentLowDate(List<Candle> orderedCandles, int period, BigDecimal recentLow) {
        return orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - period))
                .filter(candle -> candle.low().compareTo(recentLow) == 0)
                .reduce((first, second) -> second)
                .map(Candle::date)
                .orElse(null);
    }

    private Integer candlesSince(List<Candle> orderedCandles, java.time.LocalDate date) {
        if (date == null) {
            return null;
        }
        for (int i = orderedCandles.size() - 1; i >= 0; i--) {
            if (orderedCandles.get(i).date().equals(date)) {
                return orderedCandles.size() - 1 - i;
            }
        }
        return null;
    }

    private BigDecimal slope(org.ta4j.core.Indicator<org.ta4j.core.num.Num> indicator, int lastIndex, int lookback) {
        if (lastIndex < lookback) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal current = valueOrZero(indicator.getValue(lastIndex));
        BigDecimal past = valueOrZero(indicator.getValue(lastIndex - lookback));
        return percent(current.subtract(past), past);
    }

    private BigDecimal directionalVolumeAverage(List<Candle> orderedCandles, boolean bullish) {
        List<Candle> matching = orderedCandles.stream()
                .skip(Math.max(0, orderedCandles.size() - AVERAGE_VOLUME_PERIOD))
                .filter(candle -> bullish
                        ? candle.close().compareTo(candle.open()) >= 0
                        : candle.close().compareTo(candle.open()) < 0)
                .toList();
        if (matching.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return matching.stream()
                .map(Candle::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(matching.size()), 4, RoundingMode.HALF_UP);
    }

    private Trend trend(BigDecimal sma9, BigDecimal sma21) {
        int comparison = sma9.compareTo(sma21);
        if (comparison > 0) {
            return Trend.UPTREND;
        }
        if (comparison < 0) {
            return Trend.DOWNTREND;
        }
        return Trend.SIDEWAYS;
    }
}
