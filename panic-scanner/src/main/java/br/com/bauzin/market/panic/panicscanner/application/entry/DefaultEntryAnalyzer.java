package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ConfirmationStrength;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreBreakdown;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoringConfig;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntrySetupType;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic closed-daily-candle swing-entry analyzer. */
public class DefaultEntryAnalyzer implements EntryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DefaultEntryAnalyzer.class);
    private static final int EMA_SHORT = 9;
    private static final int EMA_LONG = 21;
    private static final int BREAKOUT_BASELINE = 20;

    private final EntryScoringConfig config;
    private final EntryScoreCalculator scoreCalculator;
    private final BreakoutEvaluator breakoutEvaluator;
    private final PullbackEvaluator pullbackEvaluator;
    private final TrendWeakeningEvaluator trendWeakeningEvaluator;
    private final OverextendedEvaluator overextendedEvaluator;

    public DefaultEntryAnalyzer(EntryScoringConfig config, EntryScoreCalculator scoreCalculator) {
        this.config = config;
        this.scoreCalculator = scoreCalculator;
        this.breakoutEvaluator = new BreakoutEvaluator();
        this.pullbackEvaluator = new PullbackEvaluator();
        this.trendWeakeningEvaluator = new TrendWeakeningEvaluator();
        this.overextendedEvaluator = new OverextendedEvaluator();
    }

    @Override
    public EntryAnalysis analyze(TechnicalAnalysis analysis, List<Candle> closedCandles) {
        List<Candle> candles = closedCandles.stream()
                .filter(candle -> candle.status() == CandleStatus.CLOSED)
                .sorted(Comparator.comparing(Candle::date))
                .toList();
        int requiredHistory = Math.max(EMA_LONG + config.movingAverageSlopeLookback(), BREAKOUT_BASELINE + 1);
        if (candles.size() < requiredHistory) {
            EntryChecks checks = emptyChecks(false);
            return result(EntryStatus.NO_ENTRY_SETUP, EntrySetupType.NONE, ConfirmationStrength.NONE, zeroBreakdown(), checks,
                    List.of("Insufficient closed candle history for entry analysis"),
                    analysis, BigDecimal.ZERO, BigDecimal.ZERO, false, false, false, false, false);
        }

        Candle latest = candles.get(candles.size() - 1);
        List<BigDecimal> ema9 = ema(candles, EMA_SHORT);
        List<BigDecimal> ema21 = ema(candles, EMA_LONG);
        boolean trendQualified = analysis.trend() == Trend.UPTREND;
        boolean priceAboveSma21 = analysis.lastClose().compareTo(analysis.sma21()) > 0;
        boolean ema9AboveEma21 = analysis.ema9().compareTo(analysis.ema21()) > 0;
        boolean adxAccepted = analysis.adx14().compareTo(config.minimumAdx()) >= 0;
        boolean recentHighDetected = analysis.recentHigh() != null && analysis.recentHigh().compareTo(BigDecimal.ZERO) > 0;
        boolean pullbackDepthAccepted = between(
                analysis.pullbackDepthPercent(),
                config.minimumPullbackPercent(),
                config.maximumPullbackPercent());
        boolean touchedShortAverage = touchedAverage(candles, ema9, ema21);
        boolean sma21Preserved = priceAboveSma21 || distanceBelow(analysis.lastClose(), analysis.sma21())
                .compareTo(config.averageTouchTolerancePercent()) <= 0;
        boolean rsiEntryRangeAccepted = between(analysis.rsi9(), config.minimumRsi(), config.maximumRsi());
        boolean closedAbovePreviousHigh = analysis.lastClose().compareTo(analysis.previousCandleHigh()) > 0;
        boolean latestCloseAbovePreviousClose = analysis.lastClose().compareTo(analysis.previousCandleClose()) > 0;
        boolean latestCloseAboveEma9 = analysis.lastClose().compareTo(analysis.ema9()) > 0;
        boolean latestCloseAboveEma21 = analysis.lastClose().compareTo(analysis.ema21()) > 0;
        boolean bullishConfirmationCandle = latest.close().compareTo(latest.open()) > 0;
        boolean previousLowPreserved = previousLowPreserved(latest, candles.get(candles.size() - 2));
        boolean confirmationVolumeAccepted = analysis.latestClosedCandleVolume()
                .compareTo(analysis.averageVolume20().multiply(config.minimumConfirmationVolumeRatio())) >= 0;
        BigDecimal recentHighBeforeLatest = recentHighBeforeLatest(candles);
        BigDecimal breakoutPercentAboveResistance = breakoutPercentAboveResistance(analysis.lastClose(), recentHighBeforeLatest);
        boolean breakoutAboveRecentHigh = breakoutPercentAboveResistance.compareTo(config.minimumBreakoutPercent()) >= 0;
        boolean breakoutVolumeAccepted = analysis.relativeVolume20().compareTo(config.breakoutRelativeVolume()) >= 0;
        boolean breakoutRsiAccepted = analysis.rsi9().compareTo(config.breakoutMaximumRsi()) <= 0;
        boolean breakoutAdxAccepted = analysis.adx14().compareTo(config.breakoutMinimumAdx()) >= 0
                || breakoutVolumeAccepted && positiveSlopes(analysis);
        boolean breakoutExtensionAccepted = analysis.distanceFromEma21Percent().compareTo(config.breakoutMaximumExtensionFromEma21Percent()) <= 0
                && extensionInAtr(analysis).compareTo(config.breakoutMaximumExtensionInAtr()) <= 0;
        boolean liquidityAccepted = analysis.averageFinancialVolume20().compareTo(BigDecimal.ZERO) > 0;
        boolean consolidationDetected = sideways(analysis, breakoutAboveRecentHigh);
        boolean overextendedFromEma21 = overextendedFromEma21(analysis);
        PivotState pivots = pivots(candles);
        boolean ema9SlopeNegative = analysis.movingAverageSlope9().compareTo(config.maximumSidewaysSlopePercent().negate()) < 0;
        boolean latestCloseBelowSma21 = analysis.lastClose().compareTo(analysis.sma21()) < 0;
        boolean adxWeakening = !adxAccepted;
        boolean latestSwingLowBelowPrevious = pivots.hasSwingLowPair() && !pivots.higherLowDetected();
        boolean movingAveragesConverging = movingAveragesConverging(analysis);
        boolean trendWeakening = trendWeakeningEvaluator.isWeakening(ema9SlopeNegative, !ema9AboveEma21, latestCloseBelowSma21,
                adxWeakening, latestSwingLowBelowPrevious, movingAveragesConverging);
        boolean sufficientEntryHistory = true;

        EntryChecks preliminaryChecks = new EntryChecks(
                trendQualified,
                priceAboveSma21,
                ema9AboveEma21,
                adxAccepted,
                recentHighDetected,
                pullbackDepthAccepted,
                touchedShortAverage,
                sma21Preserved,
                rsiEntryRangeAccepted,
                closedAbovePreviousHigh,
                latestCloseAbovePreviousClose,
                latestCloseAboveEma9,
                bullishConfirmationCandle,
                previousLowPreserved,
                confirmationVolumeAccepted,
                breakoutAboveRecentHigh,
                breakoutVolumeAccepted,
                liquidityAccepted,
                false,
                latestCloseAboveEma21,
                breakoutRsiAccepted,
                breakoutAdxAccepted,
                breakoutExtensionAccepted,
                consolidationDetected,
                overextendedFromEma21,
                trendWeakening,
                ema9SlopeNegative,
                latestCloseBelowSma21,
                adxWeakening,
                latestSwingLowBelowPrevious,
                movingAveragesConverging,
                pivots.higherLowDetected(),
                sufficientEntryHistory);
        boolean breakoutEligible = breakoutEvaluator.isEligible(preliminaryChecks);
        EntryChecks checks = new EntryChecks(
                trendQualified,
                priceAboveSma21,
                ema9AboveEma21,
                adxAccepted,
                recentHighDetected,
                pullbackDepthAccepted,
                touchedShortAverage,
                sma21Preserved,
                rsiEntryRangeAccepted,
                closedAbovePreviousHigh,
                latestCloseAbovePreviousClose,
                latestCloseAboveEma9,
                bullishConfirmationCandle,
                previousLowPreserved,
                confirmationVolumeAccepted,
                breakoutAboveRecentHigh,
                breakoutVolumeAccepted,
                liquidityAccepted,
                breakoutEligible,
                latestCloseAboveEma21,
                breakoutRsiAccepted,
                breakoutAdxAccepted,
                breakoutExtensionAccepted,
                consolidationDetected,
                overextendedFromEma21,
                trendWeakening,
                ema9SlopeNegative,
                latestCloseBelowSma21,
                adxWeakening,
                latestSwingLowBelowPrevious,
                movingAveragesConverging,
                pivots.higherLowDetected(),
                sufficientEntryHistory);

        boolean pullbackDetected = pullbackEvaluator.isDetected(checks)
                && analysis.rsi9().compareTo(config.minimumRsi()) >= 0;
        ConfirmationStrength pullbackConfirmationStrength = pullbackEvaluator.confirmationStrength(checks, pullbackDetected);
        boolean pullbackReady = pullbackEvaluator.isReady(pullbackConfirmationStrength, sufficientEntryHistory);
        boolean breakoutReady = breakoutEvaluator.isReady(analysis, checks, config) && sufficientEntryHistory;
        boolean extensionDetected = overextendedFromEma21
                || analysis.rsi9().compareTo(config.maximumRsi()) > 0
                || extensionInAtr(analysis).compareTo(config.maximumExtensionInAtr()) > 0;
        boolean overextended = overextendedEvaluator.isOverextended(extensionDetected, breakoutReady, pullbackReady);
        ConfirmationStrength confirmationStrength = breakoutReady ? ConfirmationStrength.STRONG : pullbackConfirmationStrength;

        EntryScoreBreakdown pullbackScore = scoreCalculator.calculatePullback(checks, analysis, latest, config);
        EntryScoreBreakdown breakoutScore = scoreCalculator.calculateBreakout(checks, analysis, config);
        breakoutReady = breakoutReady && breakoutScore.total() >= config.breakoutMinimumScore();
        log.debug(
                "panic-scanner breakout ticker={} eligible={} resistance={} latestClose={} breakoutPercent={} relativeVolume={} rsi={} adx={} extensionEma21={} extensionAtr={} finalStatus={} rejectionReasons={}",
                analysis.ticker(),
                breakoutEligible,
                recentHighBeforeLatest,
                analysis.lastClose(),
                breakoutPercentAboveResistance,
                analysis.relativeVolume20(),
                analysis.rsi9(),
                analysis.adx14(),
                analysis.distanceFromEma21Percent(),
                extensionInAtr(analysis),
                breakoutReady ? EntryStatus.BREAKOUT_READY : EntryStatus.NO_ENTRY_SETUP,
                breakoutRejectionReasons(checks, breakoutScore.total()));
        EntrySetupType setupType = selectedSetupType(pullbackDetected, pullbackReady, breakoutReady, pullbackScore.total(), breakoutScore.total());
        EntryScoreBreakdown selectedScore = setupType == EntrySetupType.BREAKOUT ? breakoutScore
                : setupType == EntrySetupType.PULLBACK ? pullbackScore
                : pullbackScore.total() >= breakoutScore.total() ? pullbackScore : breakoutScore;
        EntryStatus status = status(checks, pullbackDetected, pullbackReady, breakoutReady,
                overextended, consolidationDetected, selectedScore.total());
        return result(status, setupType, confirmationStrength, selectedScore, checks,
                reasons(status, confirmationStrength, pullbackDetected, breakoutReady, consolidationDetected, overextended, checks),
                analysis, recentHighBeforeLatest, breakoutPercentAboveResistance, breakoutReady, pullbackDetected, pullbackReady, overextended, consolidationDetected);
    }

    private EntryStatus status(EntryChecks checks,
                               boolean pullbackDetected,
                               boolean pullbackReady,
                               boolean breakoutReady,
                               boolean overextended,
                               boolean sideways,
                               int score) {
        if (!checks.trendQualified() || !checks.priceAboveSma21() || !checks.ema9AboveEma21()) {
            return EntryStatus.INVALIDATED;
        }
        if (breakoutReady) {
            return EntryStatus.BREAKOUT_READY;
        }
        if (checks.trendWeakening()) {
            return EntryStatus.TREND_WEAKENING;
        }
        if (pullbackReady && score >= config.minimumEntryScore()) {
            return EntryStatus.PULLBACK_READY;
        }
        if (pullbackDetected) {
            return EntryStatus.PULLBACK_IN_PROGRESS;
        }
        if (sideways) {
            return EntryStatus.WAIT_BREAKOUT;
        }
        if (!overextended) {
            return EntryStatus.WATCH;
        }
        return EntryStatus.OVEREXTENDED;
    }

    private EntrySetupType selectedSetupType(boolean pullbackDetected, boolean pullbackConfirmed, boolean breakoutConfirmed, int pullbackScore, int breakoutScore) {
        if (breakoutConfirmed) {
            return EntrySetupType.BREAKOUT;
        }
        if ((pullbackDetected || pullbackConfirmed) && (!breakoutConfirmed || pullbackScore >= breakoutScore)) {
            return EntrySetupType.PULLBACK;
        }
        return EntrySetupType.NONE;
    }

    private List<String> breakoutRejectionReasons(EntryChecks checks, int breakoutScore) {
        List<String> reasons = new ArrayList<>();
        if (!checks.breakoutEligible()) reasons.add("not eligible");
        if (!checks.breakoutAboveRecentHigh()) reasons.add("no resistance break");
        if (!checks.breakoutVolumeAccepted()) reasons.add("volume below threshold");
        if (!checks.bullishConfirmationCandle()) reasons.add("not bullish candle");
        if (!checks.breakoutRsiAccepted()) reasons.add("rsi above breakout maximum");
        if (!checks.breakoutAdxAccepted()) reasons.add("adx below breakout minimum");
        if (!checks.breakoutExtensionAccepted()) reasons.add("breakout extension too high");
        if (breakoutScore < config.breakoutMinimumScore()) reasons.add("score below breakout minimum");
        return reasons;
    }

    private List<String> reasons(EntryStatus status,
                                 ConfirmationStrength confirmationStrength,
                                 boolean pullbackDetected,
                                 boolean breakoutConfirmed,
                                 boolean sideways,
                                 boolean overextended,
                                 EntryChecks checks) {
        List<String> reasons = new ArrayList<>();
        if (status == EntryStatus.BREAKOUT_READY) {
            reasons.add("Confirmed breakout above recent resistance with volume confirmation");
        }
        if (status == EntryStatus.PULLBACK_READY) {
            reasons.add("Confirmed pullback setup reached the minimum entry score");
        }
        if (status == EntryStatus.PULLBACK_READY && pullbackDetected) {
            if (confirmationStrength == ConfirmationStrength.STRONG) {
                reasons.add("Strong pullback confirmation: latest close is above the previous candle high");
            } else if (confirmationStrength == ConfirmationStrength.MODERATE) {
                reasons.add("Moderate pullback confirmation: bullish close above EMA9 and previous close");
            }
        }
        if (status == EntryStatus.PULLBACK_IN_PROGRESS) {
            reasons.add("Pullback detected, but no moderate or strong reversal confirmation exists");
        }
        if (status == EntryStatus.TREND_WEAKENING) {
            if (checks.ema9SlopeNegative()) reasons.add("EMA9 slope turned negative");
            if (checks.latestSwingLowBelowPrevious()) reasons.add("Latest confirmed swing low is below the previous swing low");
            if (checks.adxWeakening()) reasons.add("ADX is below the configured minimum");
            if (checks.movingAveragesConverging()) reasons.add("Short moving averages are converging significantly");
        }
        if (breakoutConfirmed && status != EntryStatus.BREAKOUT_READY) {
            reasons.add("Breakout above the previous 20-candle high was confirmed");
        }
        if (sideways) reasons.add("Trend is qualified but price remains inside a consolidation range");
        if (status == EntryStatus.OVEREXTENDED && overextended) {
            reasons.add("Price ran too far from the short-term averages without a confirmed breakout or valid pullback entry");
        }
        if (reasons.isEmpty()) reasons.add("No deterministic entry setup is currently confirmed");
        return reasons;
    }

    private EntryAnalysis result(EntryStatus status,
                                 EntrySetupType setupType,
                                 ConfirmationStrength confirmationStrength,
                                 EntryScoreBreakdown score,
                                 EntryChecks checks,
                                 List<String> reasons,
                                 TechnicalAnalysis analysis,
                                 BigDecimal recentHighBeforeLatest,
                                 BigDecimal breakoutPercentAboveResistance,
                                 boolean breakoutConfirmed,
                                 boolean pullbackDetected,
                                 boolean pullbackConfirmed,
                                 boolean overextended,
                                 boolean sideways) {
        return new EntryAnalysis(
                status,
                setupType,
                confirmationStrength,
                score.total(),
                score,
                checks,
                reasons,
                analysis.recentHigh(),
                analysis.recentLow(),
                analysis.pullbackDepthPercent(),
                analysis.distanceFromEma9Percent(),
                analysis.distanceFromEma21Percent(),
                analysis.candlesSinceRecentHigh(),
                analysis.consolidationWidthPercent(),
                analysis.recentLow(),
                analysis.recentHigh(),
                recentHighBeforeLatest,
                breakoutPercentAboveResistance,
                breakoutConfirmed,
                pullbackDetected,
                pullbackConfirmed,
                overextended,
                sideways);
    }

    private EntryChecks emptyChecks(boolean sufficientHistory) {
        return new EntryChecks(false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, sufficientHistory);
    }

    private EntryScoreBreakdown zeroBreakdown() {
        return new EntryScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private boolean between(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private boolean touchedAverage(List<Candle> candles, List<BigDecimal> ema9, List<BigDecimal> ema21) {
        int start = Math.max(0, candles.size() - config.touchLookbackCandles());
        for (int i = start; i < candles.size(); i++) {
            if (distance(candles.get(i).low(), ema9.get(i)).compareTo(config.averageTouchTolerancePercent()) <= 0
                    || distance(candles.get(i).low(), ema21.get(i)).compareTo(config.averageTouchTolerancePercent()) <= 0) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal distance(BigDecimal value, BigDecimal average) {
        return value.subtract(average).abs().multiply(BigDecimal.valueOf(100)).divide(average, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal distanceBelow(BigDecimal price, BigDecimal average) {
        if (price.compareTo(average) >= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return average.subtract(price).multiply(BigDecimal.valueOf(100)).divide(average, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal recentHighBeforeLatest(List<Candle> candles) {
        if (candles.size() <= BREAKOUT_BASELINE) {
            return BigDecimal.ZERO;
        }
        return candles.stream()
                .skip(candles.size() - BREAKOUT_BASELINE - 1L)
                .limit(BREAKOUT_BASELINE)
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal breakoutPercentAboveResistance(BigDecimal latestClose, BigDecimal recentHighBeforeLatest) {
        if (recentHighBeforeLatest.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return latestClose.subtract(recentHighBeforeLatest)
                .multiply(BigDecimal.valueOf(100))
                .divide(recentHighBeforeLatest, 4, RoundingMode.HALF_UP);
    }

    private boolean sideways(TechnicalAnalysis analysis, boolean breakoutConfirmed) {
        BigDecimal maDistance = distance(analysis.sma9(), analysis.sma21());
        return analysis.consolidationWidthPercent().compareTo(config.maximumConsolidationWidthPercent()) <= 0
                && maDistance.compareTo(config.maximumMovingAverageDistancePercent()) <= 0
                && analysis.movingAverageSlope9().abs().compareTo(config.maximumSidewaysSlopePercent()) <= 0
                && analysis.movingAverageSlope21().abs().compareTo(config.maximumSidewaysSlopePercent()) <= 0
                && !breakoutConfirmed;
    }

    private boolean overextendedFromEma21(TechnicalAnalysis analysis) {
        return analysis.distanceFromEma21Percent().compareTo(config.maximumDistanceFromEma21Percent()) > 0;
    }

    private boolean previousLowPreserved(Candle latest, Candle previous) {
        BigDecimal toleratedLow = previous.low()
                .multiply(BigDecimal.ONE.subtract(config.averageTouchTolerancePercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        return latest.low().compareTo(toleratedLow) >= 0;
    }

    private boolean movingAveragesConverging(TechnicalAnalysis analysis) {
        BigDecimal distance = distance(analysis.ema9(), analysis.ema21());
        return distance.compareTo(config.maximumMovingAverageDistancePercent()) <= 0
                && analysis.movingAverageSlope9().compareTo(analysis.movingAverageSlope21()) < 0;
    }

    private BigDecimal extensionInAtr(TechnicalAnalysis analysis) {
        if (analysis.atr14().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return analysis.lastClose().subtract(analysis.ema21()).divide(analysis.atr14(), 4, RoundingMode.HALF_UP);
    }

    private boolean positiveSlopes(TechnicalAnalysis analysis) {
        return analysis.movingAverageSlope9().compareTo(BigDecimal.ZERO) > 0
                && analysis.movingAverageSlope21().compareTo(BigDecimal.ZERO) > 0;
    }

    private List<BigDecimal> ema(List<Candle> candles, int period) {
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), 8, RoundingMode.HALF_UP);
        BigDecimal ema = candles.get(0).close();
        for (Candle candle : candles) {
            ema = candle.close().subtract(ema).multiply(multiplier).add(ema);
            values.add(ema.setScale(4, RoundingMode.HALF_UP));
        }
        return values;
    }

    private PivotState pivots(List<Candle> candles) {
        List<Candle> lows = new ArrayList<>();
        for (int i = config.pivotLeftBars(); i < candles.size() - config.pivotRightBars(); i++) {
            Candle current = candles.get(i);
            if (isSwingLow(candles, i)) {
                lows.add(current);
            }
        }
        if (lows.size() < 2) {
            return new PivotState(false, false);
        }
        Candle previous = lows.get(lows.size() - 2);
        Candle latest = lows.get(lows.size() - 1);
        return new PivotState(true, latest.low().compareTo(previous.low()) > 0);
    }

    private boolean isSwingLow(List<Candle> candles, int i) {
        BigDecimal low = candles.get(i).low();
        return low.compareTo(candles.get(i - 1).low()) < 0
                && low.compareTo(candles.get(i - 2).low()) < 0
                && low.compareTo(candles.get(i + 1).low()) <= 0
                && low.compareTo(candles.get(i + 2).low()) <= 0;
    }

    private record PivotState(boolean hasSwingLowPair, boolean higherLowDetected) {
    }
}
