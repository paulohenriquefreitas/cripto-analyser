package br.com.bauzin.market.panic.panicscanner.domain.win.scoring;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinSellScoreCalculator {

    public int calculate(WinMarketState state, WinTechnicalSnapshot context, int sellerExhaustionScore) {
        int score = switch (state) {
            case STRONG_DOWNTREND -> 60;
            case DOWNTREND -> 48;
            case DOWNTREND_WEAKENING -> 32;
            case UPTREND_WEAKENING -> 28;
            case CONSOLIDATION -> 20;
            default -> 10;
        };
        if (context.lowerLowDetected()) score += 10;
        if (context.lowerHighDetected()) score += 10;
        if (context.rsi9().compareTo(BigDecimal.valueOf(50)) <= 0) score += 8;
        if (context.closeBelowEma21()) score += 8;
        score -= sellerExhaustionScore / 5;
        return Math.max(0, Math.min(100, score));
    }
}
