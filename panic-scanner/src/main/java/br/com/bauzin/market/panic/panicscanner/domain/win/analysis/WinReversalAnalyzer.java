package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinReversalAnalyzer {

    public boolean candidateUp(WinMarketState state, WinTechnicalSnapshot context, int sellerExhaustionScore) {
        return state != WinMarketState.STRONG_UPTREND
                && sellerExhaustionScore >= 65
                && context.higherLowDetected()
                && context.rsi9().compareTo(BigDecimal.valueOf(35)) >= 0;
    }

    public boolean confirmedUp(WinMarketState state, WinTechnicalSnapshot context, WinTechnicalSnapshot execution, WinFlowSnapshot flow, int sellerExhaustionScore) {
        boolean flowAccepted = !flow.available() || flow.delta() != null && flow.delta().signum() > 0;
        return state != WinMarketState.STRONG_UPTREND
                && sellerExhaustionScore >= 65
                && context.higherLowDetected()
                && context.higherHighDetected()
                && context.closeAboveEma21()
                && context.ema9().compareTo(context.ema21()) > 0
                && context.rsi9().compareTo(BigDecimal.valueOf(50)) > 0
                && execution.higherHighDetected()
                && flowAccepted;
    }

    public boolean candidateDown(WinMarketState state, WinTechnicalSnapshot context, int buyerExhaustionScore) {
        return state != WinMarketState.STRONG_DOWNTREND
                && buyerExhaustionScore >= 65
                && context.lowerHighDetected()
                && context.rsi9().compareTo(BigDecimal.valueOf(65)) <= 0;
    }

    public boolean confirmedDown(WinMarketState state, WinTechnicalSnapshot context, WinTechnicalSnapshot execution, WinFlowSnapshot flow, int buyerExhaustionScore) {
        boolean flowAccepted = !flow.available() || flow.delta() != null && flow.delta().signum() < 0;
        return state != WinMarketState.STRONG_DOWNTREND
                && buyerExhaustionScore >= 65
                && context.lowerHighDetected()
                && context.lowerLowDetected()
                && context.closeBelowEma21()
                && context.ema9().compareTo(context.ema21()) < 0
                && context.rsi9().compareTo(BigDecimal.valueOf(50)) < 0
                && execution.lowerLowDetected()
                && flowAccepted;
    }
}
