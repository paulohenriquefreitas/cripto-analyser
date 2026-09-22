package br.com.bauzin.market.panic.panicscanner.application.win.structure;

/** All distances are WIN price points, not tick counts. */
public record MarketStructureConfig(
        double nearDistancePoints,
        double exitDistancePoints,
        double touchTolerancePoints,
        int approachObservationCount,
        double approachMinimumReductionPoints) {

    public static MarketStructureConfig defaults() {
        return new MarketStructureConfig(10, 20, 2.5, 3, 5);
    }

    public MarketStructureConfig {
        if (!Double.isFinite(nearDistancePoints) || nearDistancePoints <= 0)
            throw new IllegalArgumentException("nearDistancePoints must be finite and positive");
        if (!Double.isFinite(exitDistancePoints) || exitDistancePoints <= nearDistancePoints)
            throw new IllegalArgumentException("exitDistancePoints must be greater than nearDistancePoints");
        if (!Double.isFinite(touchTolerancePoints) || touchTolerancePoints < 0
                || touchTolerancePoints > nearDistancePoints)
            throw new IllegalArgumentException("touchTolerancePoints must be between zero and nearDistancePoints");
        if (approachObservationCount < 2)
            throw new IllegalArgumentException("approachObservationCount must be at least two");
        if (!Double.isFinite(approachMinimumReductionPoints) || approachMinimumReductionPoints < 0)
            throw new IllegalArgumentException("approachMinimumReductionPoints must be finite and non-negative");
    }
}
