package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.time.Duration;
import java.util.Objects;

public record OutcomeSpecification(
        double targetPoints,
        double stopPoints,
        Duration maxHorizon) {
    public OutcomeSpecification {
        if (!Double.isFinite(targetPoints) || targetPoints <= 0) {
            throw new IllegalArgumentException("targetPoints must be finite and positive");
        }
        if (!Double.isFinite(stopPoints) || stopPoints <= 0) {
            throw new IllegalArgumentException("stopPoints must be finite and positive");
        }
        Objects.requireNonNull(maxHorizon, "maxHorizon must not be null");
        if (maxHorizon.isZero() || maxHorizon.isNegative()) {
            throw new IllegalArgumentException("maxHorizon must be positive");
        }
    }
}
