package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinTrendAnalyzer {

    public WinMarketState analyze(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        if (context.consolidationWidth().compareTo(context.atr14().multiply(config.consolidation().maximumWidthAtr())) <= 0
                && context.movingAverageSlope9().abs().compareTo(config.consolidation().maximumSlopePercent()) <= 0
                && context.movingAverageSlope21().abs().compareTo(config.consolidation().maximumSlopePercent()) <= 0) {
            return WinMarketState.CONSOLIDATION;
        }

        boolean bullishStack = context.ema9().compareTo(context.ema21()) > 0
                && context.sma9().compareTo(context.sma21()) > 0
                && context.movingAverageSlope9().signum() > 0
                && context.movingAverageSlope21().signum() > 0;
        boolean bearishStack = context.ema9().compareTo(context.ema21()) < 0
                && context.sma9().compareTo(context.sma21()) < 0
                && context.movingAverageSlope9().signum() < 0
                && context.movingAverageSlope21().signum() < 0;
        boolean adxAccepted = context.adx14().compareTo(config.trend().minimumAdx()) >= 0;

        if (bullishStack && context.higherHighDetected() && context.higherLowDetected() && adxAccepted) {
            return WinMarketState.STRONG_UPTREND;
        }
        if (bearishStack && context.lowerHighDetected() && context.lowerLowDetected() && adxAccepted) {
            return WinMarketState.STRONG_DOWNTREND;
        }
        if (bullishStack || context.ema9().compareTo(context.ema21()) > 0) {
            return weakeningUp(context) ? WinMarketState.UPTREND_WEAKENING : WinMarketState.UPTREND;
        }
        if (bearishStack || context.ema9().compareTo(context.ema21()) < 0) {
            return weakeningDown(context) ? WinMarketState.DOWNTREND_WEAKENING : WinMarketState.DOWNTREND;
        }
        return WinMarketState.CONSOLIDATION;
    }

    public int trendScore(WinMarketState state, WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        int score = switch (state) {
            case STRONG_UPTREND, STRONG_DOWNTREND -> 70;
            case UPTREND, DOWNTREND -> 52;
            case UPTREND_WEAKENING, DOWNTREND_WEAKENING -> 42;
            case CONSOLIDATION -> 20;
        };
        if (context.adx14().compareTo(config.trend().minimumAdx()) >= 0) score += 12;
        if (context.movingAverageSlope9().abs().compareTo(new BigDecimal("0.12")) >= 0) score += 8;
        if (context.higherHighDetected() || context.lowerLowDetected()) score += 5;
        if (context.higherLowDetected() || context.lowerHighDetected()) score += 5;
        return clamp(score);
    }

    private boolean weakeningUp(WinTechnicalSnapshot context) {
        return context.movingAverageSlope9().signum() <= 0
                || context.rsi9().compareTo(BigDecimal.valueOf(50)) < 0
                || context.lowerLowDetected();
    }

    private boolean weakeningDown(WinTechnicalSnapshot context) {
        return context.movingAverageSlope9().signum() >= 0
                || context.rsi9().compareTo(BigDecimal.valueOf(50)) > 0
                || context.higherHighDetected();
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
