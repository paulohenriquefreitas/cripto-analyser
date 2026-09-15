package br.com.bauzin.market.panic.panicscanner.domain.win.scoring;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinBuyScoreCalculator {

    public int calculate(WinMarketState state, WinTechnicalSnapshot context, int buyerExhaustionScore) {
        int score = switch (state) {
            case STRONG_UPTREND -> 60;
            case UPTREND -> 48;
            case UPTREND_WEAKENING -> 32;
            case DOWNTREND_WEAKENING -> 28;
            case CONSOLIDATION -> 20;
            default -> 10;
        };
        if (context.higherHighDetected()) score += 10;
        if (context.higherLowDetected()) score += 10;
        if (context.rsi9().compareTo(BigDecimal.valueOf(50)) >= 0) score += 8;
        if (context.closeAboveEma21()) score += 8;
        score -= buyerExhaustionScore / 5;
        return Math.max(0, Math.min(100, score));
    }
}
