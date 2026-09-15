package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;

public record WinTechnicalSnapshot(
        BigDecimal sma9,
        BigDecimal sma21,
        BigDecimal ema9,
        BigDecimal ema21,
        BigDecimal rsi9,
        BigDecimal atr14,
        BigDecimal adx14,
        BigDecimal volume,
        BigDecimal relativeVolume,
        BigDecimal sessionHigh,
        BigDecimal sessionLow,
        BigDecimal recentHigh,
        BigDecimal recentLow,
        BigDecimal vwap,
        BigDecimal distanceFromEma9Percent,
        BigDecimal distanceFromEma21Percent,
        BigDecimal distanceFromVwapPercent,
        BigDecimal distanceFromSessionHigh,
        BigDecimal distanceFromSessionLow,
        BigDecimal candleBodyPercent,
        BigDecimal upperWickPercent,
        BigDecimal lowerWickPercent,
        int consecutiveBullishCandles,
        int consecutiveBearishCandles,
        BigDecimal movingAverageSlope9,
        BigDecimal movingAverageSlope21,
        BigDecimal recentHighDistance,
        BigDecimal recentLowDistance,
        BigDecimal pullbackDepth,
        BigDecimal consolidationWidth,
        BigDecimal breakoutDistance,
        BigDecimal atrExtension,
        boolean higherHighDetected,
        boolean higherLowDetected,
        boolean lowerHighDetected,
        boolean lowerLowDetected) {

    public boolean closeAboveEma21() {
        return distanceFromEma21Percent.signum() > 0;
    }

    public boolean closeBelowEma21() {
        return distanceFromEma21Percent.signum() < 0;
    }
}
