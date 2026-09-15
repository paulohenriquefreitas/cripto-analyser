package br.com.bauzin.market.panic.panicscanner.domain.scoring;

import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates the explicit 0..100 momentum score from domain values. */
public class ScoreCalculator {

    public static final int SCORE_MIN = 0;
    public static final int SCORE_MAX = 100;
    private static final int CLOSE_ABOVE_SMA_POINTS = 15;
    private static final int SMA_ALIGNMENT_POINTS = 20;
    private static final int RSI_RANGE_POINTS = 15;
    private static final int ADX_MAX_POINTS = 15;
    private static final int LIQUIDITY_MAX_POINTS = 10;
    private static final int RELATIVE_VOLUME_MAX_POINTS = 10;
    private static final int HEALTHY_DISTANCE_MAX_POINTS = 10;
    private static final int POSITIVE_RETURN_20D_POINTS = 5;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public int calculate(TechnicalChecks checks) {
        return Math.min(SCORE_MAX,
                points(checks.closeAboveSma21(), CLOSE_ABOVE_SMA_POINTS)
                        + points(checks.sma9AboveSma21(), SMA_ALIGNMENT_POINTS)
                        + points(checks.rsiInsideRange(), RSI_RANGE_POINTS)
                        + points(checks.adxAccepted(), ADX_MAX_POINTS)
                        + points(checks.liquidityAccepted(), LIQUIDITY_MAX_POINTS));
    }

    public int calculate(TechnicalChecks checks, TechnicalAnalysis analysis, MomentumScoringConfig config) {
        int score = SCORE_MIN;
        score += points(checks.closeAboveSma21(), CLOSE_ABOVE_SMA_POINTS);
        score += points(checks.sma9AboveSma21(), SMA_ALIGNMENT_POINTS);
        score += points(checks.rsiInsideRange(), RSI_RANGE_POINTS);
        score += scaledPoints(analysis.adx14(), config.minimumAdx(), ADX_MAX_POINTS);
        score += scaledPoints(analysis.averageFinancialVolume20(), config.minimumAverageFinancialVolume(), LIQUIDITY_MAX_POINTS);
        score += scaledPoints(analysis.relativeVolume20(), config.preferredRelativeVolume(), RELATIVE_VOLUME_MAX_POINTS);
        score += healthyDistancePoints(analysis.distanceFromSma21Percent(), config.maximumHealthyDistanceFromSma21Percent());
        score += analysis.return20DaysPercent().compareTo(BigDecimal.ZERO) > 0 ? POSITIVE_RETURN_20D_POINTS : 0;
        return Math.min(score, SCORE_MAX);
    }

    private int points(boolean accepted, int points) {
        return accepted ? points : 0;
    }

    private int scaledPoints(BigDecimal value, BigDecimal target, int maxPoints) {
        if (value == null || target == null || target.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        BigDecimal ratio = value.divide(target, 6, RoundingMode.HALF_UP);
        return ratio.min(BigDecimal.ONE).multiply(BigDecimal.valueOf(maxPoints)).setScale(0, RoundingMode.DOWN).intValue();
    }

    private int healthyDistancePoints(BigDecimal distancePercent, BigDecimal maxHealthyDistancePercent) {
        if (distancePercent == null || maxHealthyDistancePercent == null || maxHealthyDistancePercent.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        if (distancePercent.compareTo(BigDecimal.ZERO) < 0 || distancePercent.compareTo(maxHealthyDistancePercent) > 0) {
            return 0;
        }
        BigDecimal remaining = maxHealthyDistancePercent.subtract(distancePercent);
        return remaining.divide(maxHealthyDistancePercent, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(HEALTHY_DISTANCE_MAX_POINTS))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
    }
}
