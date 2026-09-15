package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.TradeLocation;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinExecutionStatus;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSetupType;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSignal;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTradePlan;

public class WinExecutionAnalyzer {

    public TradeLocation tradeLocation(WinTechnicalSnapshot context, boolean overextended) {
        if (overextended || context.atrExtension().compareTo(new java.math.BigDecimal("2.5")) >= 0) return TradeLocation.VERY_POOR;
        if (context.atrExtension().compareTo(new java.math.BigDecimal("1.8")) >= 0) return TradeLocation.POOR;
        if (context.distanceFromEma9Percent().abs().compareTo(new java.math.BigDecimal("0.25")) <= 0
                || context.distanceFromVwapPercent().abs().compareTo(new java.math.BigDecimal("0.30")) <= 0) {
            return TradeLocation.EXCELLENT;
        }
        if (context.atrExtension().compareTo(new java.math.BigDecimal("1.2")) <= 0) return TradeLocation.GOOD;
        return TradeLocation.ACCEPTABLE;
    }

    public Decision decide(WinDecisionInput input, PanicScannerWinProperties config) {
        if (input.consolidation()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.NO_TRADE, WinSetupType.NONE, false);
        }
        if (input.falseBreakoutUp()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.NO_TRADE, WinSetupType.FALSE_BREAKOUT_UP, false);
        }
        if (input.falseBreakdownDown()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.NO_TRADE, WinSetupType.FALSE_BREAKOUT_DOWN, false);
        }
        if (input.reversalConfirmedUp()) {
            return accepted(WinSignal.BUY, WinExecutionStatus.REVERSAL_CONFIRMED_UP, WinSetupType.REVERSAL_UP, input, config);
        }
        if (input.reversalConfirmedDown()) {
            return accepted(WinSignal.SELL, WinExecutionStatus.REVERSAL_CONFIRMED_DOWN, WinSetupType.REVERSAL_DOWN, input, config);
        }
        if (input.reversalCandidateUp()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.REVERSAL_CANDIDATE_UP, WinSetupType.REVERSAL_UP, false);
        }
        if (input.reversalCandidateDown()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.REVERSAL_CANDIDATE_DOWN, WinSetupType.REVERSAL_DOWN, false);
        }
        if (input.overextendedUp()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.OVEREXTENDED_UP, WinSetupType.NONE, false);
        }
        if (input.overextendedDown()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.OVEREXTENDED_DOWN, WinSetupType.NONE, false);
        }
        if (input.breakoutUp() || input.breakdownDown()) {
            return new Decision(WinSignal.WAIT, input.breakoutUp() ? WinExecutionStatus.WATCH_BUY : WinExecutionStatus.WATCH_SELL, WinSetupType.WAIT_RETEST, false);
        }
        if (input.buyTriggered()) {
            return accepted(WinSignal.BUY, WinExecutionStatus.BUY_TRIGGERED, WinSetupType.BUY_PULLBACK, input, config);
        }
        if (input.sellTriggered()) {
            return accepted(WinSignal.SELL, WinExecutionStatus.SELL_TRIGGERED, WinSetupType.SELL_PULLBACK, input, config);
        }
        if (input.buySetup()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.BUY_SETUP, WinSetupType.BUY_PULLBACK, false);
        }
        if (input.sellSetup()) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.SELL_SETUP, WinSetupType.SELL_PULLBACK, false);
        }
        if (input.marketState() == WinMarketState.STRONG_UPTREND || input.marketState() == WinMarketState.UPTREND) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.WATCH_BUY, WinSetupType.BUY_PULLBACK, false);
        }
        if (input.marketState() == WinMarketState.STRONG_DOWNTREND || input.marketState() == WinMarketState.DOWNTREND) {
            return new Decision(WinSignal.WAIT, WinExecutionStatus.WATCH_SELL, WinSetupType.SELL_PULLBACK, false);
        }
        return new Decision(WinSignal.WAIT, WinExecutionStatus.NO_TRADE, WinSetupType.NONE, false);
    }

    private Decision accepted(WinSignal signal, WinExecutionStatus status, WinSetupType setupType, WinDecisionInput input, PanicScannerWinProperties config) {
        if (input.tradeLocation() == TradeLocation.POOR || input.tradeLocation() == TradeLocation.VERY_POOR) {
            return new Decision(WinSignal.WAIT, status, setupType, false);
        }
        if (input.executionScore() < config.execution().minimumScore()) {
            return new Decision(WinSignal.WAIT, status, setupType, false);
        }
        if (!input.rewardRiskAccepted()) {
            return new Decision(WinSignal.WAIT, status, setupType, false);
        }
        return new Decision(signal, status, setupType, true);
    }

    public record Decision(WinSignal signal, WinExecutionStatus executionStatus, WinSetupType setupType, boolean tradeAllowed) {
    }

    public record WinDecisionInput(
            WinMarketState marketState,
            boolean consolidation,
            boolean overextendedUp,
            boolean overextendedDown,
            boolean buySetup,
            boolean sellSetup,
            boolean buyTriggered,
            boolean sellTriggered,
            boolean breakoutUp,
            boolean breakdownDown,
            boolean falseBreakoutUp,
            boolean falseBreakdownDown,
            boolean reversalCandidateUp,
            boolean reversalCandidateDown,
            boolean reversalConfirmedUp,
            boolean reversalConfirmedDown,
            TradeLocation tradeLocation,
            int executionScore,
            WinTradePlan tradePlan,
            boolean rewardRiskAccepted) {
    }
}
