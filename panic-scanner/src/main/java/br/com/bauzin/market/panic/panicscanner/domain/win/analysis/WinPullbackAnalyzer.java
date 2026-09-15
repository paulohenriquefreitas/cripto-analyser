package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.math.BigDecimal;

public class WinPullbackAnalyzer {

    public boolean buyPullback(WinMarketState state, WinTechnicalSnapshot context, WinTechnicalSnapshot execution, PanicScannerWinProperties config) {
        return bullishState(state)
                && nearPullbackArea(context, config)
                && context.pullbackDepth().compareTo(context.atr14().multiply(config.pullback().maximumDepthAtr())) <= 0
                && rejectionUp(context)
                && context.higherLowDetected()
                && executionTriggerUp(execution);
    }

    public boolean sellPullback(WinMarketState state, WinTechnicalSnapshot context, WinTechnicalSnapshot execution, PanicScannerWinProperties config) {
        return bearishState(state)
                && nearPullbackArea(context, config)
                && context.pullbackDepth().compareTo(context.atr14().multiply(config.pullback().maximumDepthAtr())) <= 0
                && rejectionDown(context)
                && context.lowerHighDetected()
                && executionTriggerDown(execution);
    }

    public boolean buySetupWithoutTrigger(WinMarketState state, WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return bullishState(state) && nearPullbackArea(context, config) && rejectionUp(context) && context.higherLowDetected();
    }

    public boolean sellSetupWithoutTrigger(WinMarketState state, WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return bearishState(state) && nearPullbackArea(context, config) && rejectionDown(context) && context.lowerHighDetected();
    }

    private boolean nearPullbackArea(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        BigDecimal maxDistance = context.atr14().multiply(config.pullback().maximumDepthAtr());
        return context.distanceFromEma9Percent().abs().compareTo(new BigDecimal("0.25")) <= 0
                || context.distanceFromEma21Percent().abs().compareTo(new BigDecimal("0.35")) <= 0
                || context.distanceFromVwapPercent().abs().compareTo(new BigDecimal("0.35")) <= 0
                || context.pullbackDepth().compareTo(maxDistance) <= 0;
    }

    private boolean rejectionUp(WinTechnicalSnapshot context) {
        return context.lowerWickPercent().compareTo(BigDecimal.valueOf(35)) >= 0
                && context.rsi9().compareTo(BigDecimal.valueOf(45)) >= 0;
    }

    private boolean rejectionDown(WinTechnicalSnapshot context) {
        return context.upperWickPercent().compareTo(BigDecimal.valueOf(35)) >= 0
                && context.rsi9().compareTo(BigDecimal.valueOf(55)) <= 0;
    }

    private boolean executionTriggerUp(WinTechnicalSnapshot execution) {
        return execution.higherHighDetected()
                && execution.ema9().compareTo(execution.ema21()) > 0
                && execution.rsi9().compareTo(BigDecimal.valueOf(50)) >= 0;
    }

    private boolean executionTriggerDown(WinTechnicalSnapshot execution) {
        return execution.lowerLowDetected()
                && execution.ema9().compareTo(execution.ema21()) < 0
                && execution.rsi9().compareTo(BigDecimal.valueOf(50)) <= 0;
    }

    private boolean bullishState(WinMarketState state) {
        return state == WinMarketState.STRONG_UPTREND || state == WinMarketState.UPTREND;
    }

    private boolean bearishState(WinMarketState state) {
        return state == WinMarketState.STRONG_DOWNTREND || state == WinMarketState.DOWNTREND;
    }
}
