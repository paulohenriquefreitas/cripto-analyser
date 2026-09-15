package br.com.bauzin.market.panic.panicscanner.domain.win.scoring;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinExecutionScoreCalculator {

    public int calculateBuy(WinTechnicalSnapshot context, WinTechnicalSnapshot execution, WinFlowSnapshot flow, boolean pullbackComplete) {
        int score = base(context, execution, pullbackComplete);
        if (execution.higherHighDetected()) score += 12;
        if (execution.ema9().compareTo(execution.ema21()) > 0) score += 10;
        if (execution.rsi9().compareTo(BigDecimal.valueOf(50)) >= 0) score += 8;
        if (flow.available() && flow.delta() != null && flow.delta().signum() > 0) score += 8;
        return clamp(score);
    }

    public int calculateSell(WinTechnicalSnapshot context, WinTechnicalSnapshot execution, WinFlowSnapshot flow, boolean pullbackComplete) {
        int score = base(context, execution, pullbackComplete);
        if (execution.lowerLowDetected()) score += 12;
        if (execution.ema9().compareTo(execution.ema21()) < 0) score += 10;
        if (execution.rsi9().compareTo(BigDecimal.valueOf(50)) <= 0) score += 8;
        if (flow.available() && flow.delta() != null && flow.delta().signum() < 0) score += 8;
        return clamp(score);
    }

    private int base(WinTechnicalSnapshot context, WinTechnicalSnapshot execution, boolean pullbackComplete) {
        int score = 18;
        if (pullbackComplete) score += 22;
        if (context.distanceFromEma9Percent().abs().compareTo(new BigDecimal("0.30")) <= 0) score += 12;
        if (context.distanceFromVwapPercent().abs().compareTo(new BigDecimal("0.45")) <= 0) score += 10;
        if (context.atrExtension().compareTo(new BigDecimal("1.40")) <= 0) score += 12;
        if (execution.relativeVolume().compareTo(new BigDecimal("1.00")) >= 0) score += 8;
        return score;
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
