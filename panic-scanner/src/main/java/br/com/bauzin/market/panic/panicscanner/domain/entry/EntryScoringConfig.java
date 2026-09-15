package br.com.bauzin.market.panic.panicscanner.domain.entry;

import java.math.BigDecimal;

/** Thresholds used by deterministic entry scoring and classification. */
public record EntryScoringConfig(
        int recentHighLookback,
        int pivotLeftBars,
        int pivotRightBars,
        BigDecimal minimumPullbackPercent,
        BigDecimal maximumPullbackPercent,
        BigDecimal averageTouchTolerancePercent,
        int touchLookbackCandles,
        BigDecimal minimumRsi,
        BigDecimal maximumRsi,
        BigDecimal breakoutMaximumRsi,
        BigDecimal minimumAdx,
        int minimumEntryScore,
        BigDecimal maximumDistanceFromEma21Percent,
        BigDecimal maximumExtensionInAtr,
        BigDecimal breakoutMaximumExtensionFromEma21Percent,
        BigDecimal breakoutMaximumExtensionInAtr,
        BigDecimal minimumBreakoutPercent,
        BigDecimal breakoutMinimumAdx,
        int breakoutMinimumScore,
        BigDecimal breakoutRelativeVolume,
        BigDecimal minimumConfirmationVolumeRatio,
        BigDecimal maximumConsolidationWidthPercent,
        BigDecimal maximumMovingAverageDistancePercent,
        int movingAverageSlopeLookback,
        BigDecimal maximumSidewaysSlopePercent) {
}
