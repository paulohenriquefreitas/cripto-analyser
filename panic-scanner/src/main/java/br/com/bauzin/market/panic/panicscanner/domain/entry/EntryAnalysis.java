package br.com.bauzin.market.panic.panicscanner.domain.entry;

import java.math.BigDecimal;
import java.util.List;

/** Immutable result of deterministic entry setup analysis. */
public record EntryAnalysis(
        EntryStatus status,
        EntrySetupType setupType,
        ConfirmationStrength confirmationStrength,
        int score,
        EntryScoreBreakdown scoreBreakdown,
        EntryChecks checks,
        List<String> reasons,
        BigDecimal recentHigh,
        BigDecimal recentLow,
        BigDecimal pullbackDepthPercent,
        BigDecimal distanceFromEma9Percent,
        BigDecimal distanceFromEma21Percent,
        Integer candlesSinceRecentHigh,
        BigDecimal consolidationWidthPercent,
        BigDecimal nearestSupport,
        BigDecimal nearestResistance,
        BigDecimal recentHighBeforeLatest,
        BigDecimal breakoutPercentAboveResistance,
        boolean breakoutConfirmed,
        boolean pullbackDetected,
        boolean pullbackConfirmed,
        boolean overextended,
        boolean sideways) {

    public EntryAnalysis {
        reasons = List.copyOf(reasons);
    }
}
