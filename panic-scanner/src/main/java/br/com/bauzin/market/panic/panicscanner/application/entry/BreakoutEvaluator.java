package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoringConfig;

/** Evaluates momentum-continuation breakout entries. */
class BreakoutEvaluator {

    boolean isReady(TechnicalAnalysis analysis, EntryChecks checks, EntryScoringConfig config) {
        return checks.breakoutEligible()
                && checks.breakoutAboveRecentHigh()
                && checks.breakoutVolumeAccepted()
                && checks.bullishConfirmationCandle()
                && checks.latestCloseAbovePreviousClose()
                && checks.latestCloseAboveEma9()
                && checks.latestCloseAboveEma21()
                && checks.liquidityAccepted()
                && checks.sufficientEntryHistory()
                && checks.breakoutRsiAccepted()
                && checks.breakoutAdxAccepted()
                && checks.breakoutExtensionAccepted();
    }

    boolean isEligible(EntryChecks checks) {
        return checks.priceAboveSma21()
                && checks.ema9AboveEma21()
                && checks.liquidityAccepted()
                && checks.sufficientEntryHistory()
                && checks.recentHighDetected()
                && checks.latestCloseAboveEma9()
                && checks.latestCloseAboveEma21()
                && !checks.latestCloseBelowSma21();
    }
}
