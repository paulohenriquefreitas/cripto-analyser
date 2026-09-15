package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoringConfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Configuration for deterministic swing-entry analysis. */
@ConfigurationProperties(prefix = "panic-scanner.entry")
public record PanicScannerEntryProperties(
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

    public PanicScannerEntryProperties {
        if (recentHighLookback <= 0) recentHighLookback = 10;
        if (pivotLeftBars <= 0) pivotLeftBars = 2;
        if (pivotRightBars <= 0) pivotRightBars = 2;
        if (minimumPullbackPercent == null) minimumPullbackPercent = new BigDecimal("1.0");
        if (maximumPullbackPercent == null) maximumPullbackPercent = new BigDecimal("6.0");
        if (averageTouchTolerancePercent == null) averageTouchTolerancePercent = new BigDecimal("1.0");
        if (touchLookbackCandles <= 0) touchLookbackCandles = 3;
        if (minimumRsi == null) minimumRsi = BigDecimal.valueOf(45);
        if (maximumRsi == null) maximumRsi = BigDecimal.valueOf(65);
        if (breakoutMaximumRsi == null) breakoutMaximumRsi = BigDecimal.valueOf(72);
        if (minimumAdx == null) minimumAdx = BigDecimal.valueOf(20);
        if (minimumEntryScore <= 0) minimumEntryScore = 70;
        if (maximumDistanceFromEma21Percent == null) maximumDistanceFromEma21Percent = new BigDecimal("6.0");
        if (maximumExtensionInAtr == null) maximumExtensionInAtr = new BigDecimal("2.5");
        if (breakoutMaximumExtensionFromEma21Percent == null) breakoutMaximumExtensionFromEma21Percent = new BigDecimal("6.0");
        if (breakoutMaximumExtensionInAtr == null) breakoutMaximumExtensionInAtr = new BigDecimal("3.0");
        if (minimumBreakoutPercent == null) minimumBreakoutPercent = new BigDecimal("0.1");
        if (breakoutMinimumAdx == null) breakoutMinimumAdx = BigDecimal.valueOf(15);
        if (breakoutMinimumScore <= 0) breakoutMinimumScore = 75;
        if (breakoutRelativeVolume == null) breakoutRelativeVolume = new BigDecimal("1.2");
        if (minimumConfirmationVolumeRatio == null) minimumConfirmationVolumeRatio = new BigDecimal("0.8");
        if (maximumConsolidationWidthPercent == null) maximumConsolidationWidthPercent = new BigDecimal("8.0");
        if (maximumMovingAverageDistancePercent == null) maximumMovingAverageDistancePercent = new BigDecimal("1.5");
        if (movingAverageSlopeLookback <= 0) movingAverageSlopeLookback = 5;
        if (maximumSidewaysSlopePercent == null) maximumSidewaysSlopePercent = new BigDecimal("0.30");
    }

    public EntryScoringConfig toConfig() {
        return new EntryScoringConfig(
                recentHighLookback,
                pivotLeftBars,
                pivotRightBars,
                minimumPullbackPercent,
                maximumPullbackPercent,
                averageTouchTolerancePercent,
                touchLookbackCandles,
                minimumRsi,
                maximumRsi,
                breakoutMaximumRsi,
                minimumAdx,
                minimumEntryScore,
                maximumDistanceFromEma21Percent,
                maximumExtensionInAtr,
                breakoutMaximumExtensionFromEma21Percent,
                breakoutMaximumExtensionInAtr,
                minimumBreakoutPercent,
                breakoutMinimumAdx,
                breakoutMinimumScore,
                breakoutRelativeVolume,
                minimumConfirmationVolumeRatio,
                maximumConsolidationWidthPercent,
                maximumMovingAverageDistancePercent,
                movingAverageSlopeLookback,
                maximumSidewaysSlopePercent);
    }
}
