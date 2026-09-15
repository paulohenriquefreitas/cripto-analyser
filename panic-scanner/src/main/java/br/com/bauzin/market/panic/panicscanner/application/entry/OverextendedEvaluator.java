package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryChecks;

/** Evaluates extension only after higher-quality entry setups have been rejected. */
class OverextendedEvaluator {

    boolean isOverextended(boolean extensionDetected, boolean breakoutReady, boolean pullbackReady) {
        return extensionDetected
                && !breakoutReady
                && !pullbackReady;
    }

    boolean hasQualityEntry(EntryChecks checks) {
        return checks.breakoutAboveRecentHigh() || checks.closedAbovePreviousHigh();
    }
}
