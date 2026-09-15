package br.com.bauzin.market.panic.panicscanner.domain.scoring;

import java.math.BigDecimal;

/** Configurable thresholds used by the explicit momentum score. */
public record MomentumScoringConfig(
        BigDecimal minimumAverageFinancialVolume,
        BigDecimal minimumRsi,
        BigDecimal maximumRsi,
        BigDecimal minimumAdx,
        int minimumQualifiedScore,
        BigDecimal maximumHealthyDistanceFromSma21Percent,
        BigDecimal preferredRelativeVolume) {
}
