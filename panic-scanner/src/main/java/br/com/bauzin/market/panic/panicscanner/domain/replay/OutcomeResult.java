package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record OutcomeResult(
        RuleOccurrence occurrence,
        OutcomeStatus status,
        double mfe,
        double mae,
        OptionalLong timeToMfe,
        OptionalLong timeToMae,
        double targetPrice,
        double stopPrice,
        OptionalLong targetHitTime,
        OptionalLong stopHitTime,
        FirstBarrier firstBarrier,
        OptionalDouble finalPrice,
        OptionalLong finalTimeMsc,
        TerminationReason terminationReason) {
    public OutcomeResult {
        Objects.requireNonNull(occurrence, "occurrence must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(timeToMfe, "timeToMfe must not be null");
        Objects.requireNonNull(timeToMae, "timeToMae must not be null");
        Objects.requireNonNull(targetHitTime, "targetHitTime must not be null");
        Objects.requireNonNull(stopHitTime, "stopHitTime must not be null");
        Objects.requireNonNull(firstBarrier, "firstBarrier must not be null");
        Objects.requireNonNull(finalPrice, "finalPrice must not be null");
        Objects.requireNonNull(finalTimeMsc, "finalTimeMsc must not be null");
        Objects.requireNonNull(terminationReason, "terminationReason must not be null");
    }
}
