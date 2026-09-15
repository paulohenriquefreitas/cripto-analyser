package br.com.bauzin.market.panic.panicscanner.domain.win.scoring;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinExhaustionScoreCalculator {

    public int sellerExhaustion(WinTechnicalSnapshot context, WinFlowSnapshot flow, PanicScannerWinProperties config) {
        int score = 0;
        if (context.rsi9().compareTo(config.rsi().oversold()) <= 0) score += 22;
        if (context.atrExtension().compareTo(config.extension().maximumEma21DistanceAtr()) >= 0) score += 20;
        if (context.lowerWickPercent().compareTo(BigDecimal.valueOf(30)) >= 0) score += 16;
        if (context.candleBodyPercent().compareTo(BigDecimal.valueOf(45)) <= 0) score += 12;
        if (context.consecutiveBearishCandles() >= config.extension().consecutiveCandles()) score += 14;
        if (context.relativeVolume().compareTo(BigDecimal.ONE) < 0) score += 8;
        if (context.higherLowDetected()) score += 10;
        if (context.higherHighDetected()) score += 6;
        if (flow.available() && flow.aggressionSlope() != null && flow.aggressionSlope().signum() > 0) score += 8;
        return clamp(score);
    }

    public int buyerExhaustion(WinTechnicalSnapshot context, WinFlowSnapshot flow, PanicScannerWinProperties config) {
        int score = 0;
        if (context.rsi9().compareTo(config.rsi().overbought()) >= 0) score += 22;
        if (context.atrExtension().compareTo(config.extension().maximumEma21DistanceAtr()) >= 0) score += 20;
        if (context.upperWickPercent().compareTo(BigDecimal.valueOf(30)) >= 0) score += 16;
        if (context.candleBodyPercent().compareTo(BigDecimal.valueOf(45)) <= 0) score += 12;
        if (context.consecutiveBullishCandles() >= config.extension().consecutiveCandles()) score += 14;
        if (context.relativeVolume().compareTo(BigDecimal.ONE) < 0) score += 8;
        if (context.lowerHighDetected()) score += 10;
        if (context.lowerLowDetected()) score += 6;
        if (flow.available() && flow.aggressionSlope() != null && flow.aggressionSlope().signum() < 0) score += 8;
        return clamp(score);
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
