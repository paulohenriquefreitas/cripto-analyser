package br.com.bauzin.market.panic.panicscanner.domain.entry;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;

import java.math.BigDecimal;

/** Calculates entry timing scores independently from momentum scoring. */
public class EntryScoreCalculator {

    public EntryScoreBreakdown calculatePullback(EntryChecks checks,
                                                 TechnicalAnalysis analysis,
                                                 Candle latestCandle,
                                                 EntryScoringConfig config) {
        int trend = points(checks.trendQualified(), 15);
        int pullbackDepth = points(checks.pullbackDepthAccepted(), 15);
        int averageTouch = points(checks.touchedShortAverage(), 15);
        int sma21Preserved = points(checks.sma21Preserved(), 10);
        int rsi = points(checks.rsiEntryRangeAccepted(), 10);
        int confirmationCandle = points(checks.closedAbovePreviousHigh(), 15);
        int higherLow = points(checks.higherLowDetected(), 10);
        int volume = points(volumeAccepted(analysis, config), 5);
        int distance = points(!checks.overextendedFromEma21(), 5);
        int total = trend + pullbackDepth + averageTouch + sma21Preserved + rsi
                + confirmationCandle + higherLow + volume + distance;
        return new EntryScoreBreakdown(
                trend, pullbackDepth, averageTouch, sma21Preserved, rsi,
                confirmationCandle, higherLow, volume, distance,
                0, 0, 0, 0, 0, 0, total);
    }

    public EntryScoreBreakdown calculateBreakout(EntryChecks checks,
                                                 TechnicalAnalysis analysis,
                                                 EntryScoringConfig config) {
        int trend = points(checks.trendQualified(), 15);
        int breakout = points(checks.breakoutAboveRecentHigh(), 20);
        int relativeVolume = points(checks.breakoutVolumeAccepted(), 20);
        int adx = points(checks.breakoutAdxAccepted(), 10);
        int rsi = points(checks.breakoutRsiAccepted(), 10);
        int slopes = points(positiveSlopes(analysis), 10);
        int distance = points(checks.breakoutExtensionAccepted(), 5);
        int higherLow = points(checks.higherLowDetected(), 5);
        int recentReturn = points(analysis.return5DaysPercent().compareTo(BigDecimal.ZERO) > 0
                && checks.breakoutExtensionAccepted(), 5);
        int total = Math.min(95, trend + breakout + relativeVolume + adx + rsi + slopes + distance + higherLow + recentReturn);
        return new EntryScoreBreakdown(
                trend, 0, 0, 0, rsi,
                0, higherLow, 0, distance,
                breakout, relativeVolume, adx, slopes, recentReturn, 0, total);
    }

    private int points(boolean accepted, int points) {
        return accepted ? points : 0;
    }

    private boolean volumeAccepted(TechnicalAnalysis analysis, EntryScoringConfig config) {
        BigDecimal threshold = analysis.averageVolume20().multiply(config.minimumConfirmationVolumeRatio());
        return analysis.latestClosedCandleVolume().compareTo(threshold) >= 0;
    }

    private boolean positiveSlopes(TechnicalAnalysis analysis) {
        return analysis.movingAverageSlope9().compareTo(BigDecimal.ZERO) > 0
                && analysis.movingAverageSlope21().compareTo(BigDecimal.ZERO) > 0;
    }
}
