package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.entry.ConfirmationStrength;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryChecks;

/** Evaluates pullback setup state independently from final status priority. */
class PullbackEvaluator {

    boolean isDetected(EntryChecks checks) {
        return checks.trendQualified()
                && checks.recentHighDetected()
                && checks.pullbackDepthAccepted()
                && checks.sma21Preserved()
                && checks.ema9AboveEma21()
                && checks.adxAccepted();
    }

    ConfirmationStrength confirmationStrength(EntryChecks checks, boolean pullbackDetected) {
        if (pullbackDetected && checks.closedAbovePreviousHigh()) {
            return ConfirmationStrength.STRONG;
        }
        if (pullbackDetected
                && checks.latestCloseAbovePreviousClose()
                && checks.latestCloseAboveEma9()
                && checks.bullishConfirmationCandle()
                && checks.previousLowPreserved()
                && checks.confirmationVolumeAccepted()
                && checks.sma21Preserved()
                && checks.rsiEntryRangeAccepted()
                && checks.adxAccepted()) {
            return ConfirmationStrength.MODERATE;
        }
        return ConfirmationStrength.NONE;
    }

    boolean isReady(ConfirmationStrength confirmationStrength, boolean sufficientEntryHistory) {
        return confirmationStrength != ConfirmationStrength.NONE && sufficientEntryHistory;
    }
}
