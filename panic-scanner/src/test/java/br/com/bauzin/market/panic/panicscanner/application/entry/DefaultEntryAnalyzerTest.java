package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ConfirmationStrength;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoringConfig;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntrySetupType;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEntryAnalyzerTest {

    private final DefaultEntryAnalyzer analyzer = new DefaultEntryAnalyzer(config(), new EntryScoreCalculator());

    @Test
    void shouldReturnBreakoutReadyForConfirmedBreakout() {
        List<Candle> candles = breakoutReadyCandles();
        candles.set(25, candle(26, "128", "132", "125", "130", "2000"));

        EntryAnalysis result = analyzer.analyze(analysis("130", "100", "126", "125", "60", "25",
                "1.5", "4", "5", "7", "120", "105", "10", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.BREAKOUT_READY);
        assertThat(result.setupType()).isEqualTo(EntrySetupType.BREAKOUT);
        assertThat(result.breakoutConfirmed()).isTrue();
        assertThat(result.confirmationStrength()).isEqualTo(ConfirmationStrength.STRONG);
        assertThat(result.score()).isBetween(85, 95);
        assertThat(result.reasons()).contains("Confirmed breakout above recent resistance with volume confirmation");
    }

    @Test
    void shouldPrioritizeBreakoutReadyOverOverextended() {
        List<Candle> candles = breakoutReadyCandles();
        candles.set(25, candle(26, "128", "132", "125", "130", "2000"));

        EntryAnalysis result = analyzer.analyze(analysis("130", "100", "126", "125", "71", "25",
                "1.5", "5", "5", "7", "120", "105", "10", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.BREAKOUT_READY);
        assertThat(result.setupType()).isEqualTo(EntrySetupType.BREAKOUT);
        assertThat(result.overextended()).isFalse();
        assertThat(result.reasons()).doesNotContain("Stock is overextended for a new swing entry");
    }

    @Test
    void shouldExcludeLatestCandleFromBreakoutBaseline() {
        List<Candle> candles = trendingCandles(26);
        candles.set(25, candle(26, "130", "150", "125", "130", "2000"));

        EntryAnalysis result = analyzer.analyze(analysis("130", "100", "116", "117", "60", "25",
                "1.5", "4", "5", "7", "120", "105", "10", "1", "1"), candles);

        assertThat(result.checks().breakoutAboveRecentHigh()).isTrue();
    }

    @Test
    void shouldReturnPullbackInProgressBeforeConfirmationCandle() {
        List<Candle> candles = trendingCandles(26);
        candles.set(24, candle(25, "108", "111", "105", "108", "1000"));
        candles.set(25, candle(26, "107", "109", "104", "107", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "55", "25",
                "1.0", "3", "4", "5", "111", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_IN_PROGRESS);
        assertThat(result.pullbackDetected()).isTrue();
        assertThat(result.pullbackConfirmed()).isFalse();
        assertThat(result.setupType()).isEqualTo(EntrySetupType.PULLBACK);
        assertThat(result.reasons()).contains("Pullback detected, but no moderate or strong reversal confirmation exists");
    }

    @Test
    void shouldConfirmPullbackStronglyWhenCloseIsAbovePreviousHigh() {
        List<Candle> candles = trendingCandles(26);
        candles.set(24, candle(25, "107", "108", "104", "106", "1000"));
        candles.set(25, candle(26, "107", "109", "105", "109", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("109", "100", "105", "104", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.pullbackConfirmed()).isTrue();
        assertThat(result.confirmationStrength()).isEqualTo(ConfirmationStrength.STRONG);
        assertThat(result.setupType()).isEqualTo(EntrySetupType.PULLBACK);
        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_READY);
    }

    @Test
    void shouldConfirmPullbackModeratelyWithoutCloseAbovePreviousHigh() {
        List<Candle> candles = pullbackConfirmationCandles();
        candles.set(24, candle(25, "105", "110", "104", "106", "1000"));
        candles.set(25, candle(26, "106", "109", "104", "108", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("108", "100", "105", "104", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.pullbackConfirmed()).isTrue();
        assertThat(result.confirmationStrength()).isEqualTo(ConfirmationStrength.MODERATE);
        assertThat(result.checks().closedAbovePreviousHigh()).isFalse();
        assertThat(result.reasons()).contains("Moderate pullback confirmation: bullish close above EMA9 and previous close");
    }

    @Test
    void shouldRequireBullishCandleForModerateConfirmation() {
        List<Candle> candles = pullbackConfirmationCandles();
        candles.set(24, candle(25, "105", "110", "104", "106", "1000"));
        candles.set(25, candle(26, "109", "109", "104", "108", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("108", "100", "105", "104", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_IN_PROGRESS);
        assertThat(result.checks().bullishConfirmationCandle()).isFalse();
    }

    @Test
    void shouldRequireCloseAboveEma9ForModerateConfirmation() {
        List<Candle> candles = pullbackConfirmationCandles();
        candles.set(24, candle(25, "105", "110", "104", "106", "1000"));
        candles.set(25, candle(26, "106", "109", "104", "108", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("108", "100", "109", "106", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_IN_PROGRESS);
        assertThat(result.checks().latestCloseAboveEma9()).isFalse();
    }

    @Test
    void shouldRequirePreviousLowPreservedForModerateConfirmation() {
        List<Candle> candles = pullbackConfirmationCandles();
        candles.set(24, candle(25, "105", "110", "104", "106", "1000"));
        candles.set(25, candle(26, "106", "109", "100", "108", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("108", "100", "105", "104", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_IN_PROGRESS);
        assertThat(result.checks().previousLowPreserved()).isFalse();
    }

    @Test
    void shouldRequireSufficientVolumeForModerateConfirmation() {
        DefaultEntryAnalyzer strictVolumeAnalyzer = new DefaultEntryAnalyzer(configWithVolumeRatio("1.20"), new EntryScoreCalculator());
        List<Candle> candles = pullbackConfirmationCandles();
        candles.set(24, candle(25, "105", "110", "104", "106", "1000"));
        candles.set(25, candle(26, "106", "109", "104", "108", "700"));

        EntryAnalysis result = strictVolumeAnalyzer.analyze(analysis("108", "100", "105", "104", "55", "25",
                "1.0", "3", "3", "9", "112", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.PULLBACK_IN_PROGRESS);
        assertThat(result.checks().confirmationVolumeAccepted()).isFalse();
    }

    @Test
    void shouldReturnWaitBreakoutForSidewaysQualifiedTrend() {
        List<Candle> candles = slightUpConsolidationCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("105", "104.8", "105.0", "104.7", "55", "25",
                "1.0", "1", "0.5", "2", "106", "101", "1", "0.1", "0.1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.WAIT_BREAKOUT);
        assertThat(result.sideways()).isTrue();
    }

    @Test
    void shouldReturnOverextendedWhenDistanceFromEma21IsExcessive() {
        List<Candle> candles = trendingCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("130", "100", "115", "110", "60", "25",
                "1.0", "10", "0.5", "5", "132", "105", "1", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.OVEREXTENDED);
        assertThat(result.overextended()).isTrue();
    }

    @Test
    void shouldReturnInvalidatedWhenCloseIsBelowSma21() {
        List<Candle> candles = trendingCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("99", "100", "105", "106", "55", "25",
                "1.0", "1", "3", "5", "110", "95", "3", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.INVALIDATED);
    }

    @Test
    void shouldNotReturnTrendWeakeningWhenRsiIsOutsideEntryRangeOnly() {
        List<Candle> candles = trendingCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "70", "25",
                "1.0", "3", "4", "9", "111", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isNotEqualTo(EntryStatus.TREND_WEAKENING);
        assertThat(result.checks().trendWeakening()).isFalse();
    }

    @Test
    void shouldNotReturnTrendWeakeningWhenHigherLowIsMissingWithoutLowerSwingPair() {
        List<Candle> candles = trendingCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "55", "25",
                "1.0", "3", "4", "9", "111", "102", "4", "1", "1"), candles);

        assertThat(result.checks().higherLowDetected()).isFalse();
        assertThat(result.checks().latestSwingLowBelowPrevious()).isFalse();
        assertThat(result.checks().trendWeakening()).isFalse();
        assertThat(result.status()).isNotEqualTo(EntryStatus.TREND_WEAKENING);
    }

    @Test
    void shouldReturnTrendWeakeningWhenEma9SlopeTurnsNegative() {
        List<Candle> candles = trendingCandles(26);

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "55", "25",
                "1.0", "3", "4", "9", "111", "102", "4", "-1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.TREND_WEAKENING);
        assertThat(result.reasons()).contains("EMA9 slope turned negative");
    }

    @Test
    void shouldReturnTrendWeakeningWhenLatestConfirmedSwingLowIsLower() {
        List<Candle> candles = trendingCandles(26);
        candles.set(7, candle(8, "108", "110", "97", "108", "1000"));
        candles.set(17, candle(18, "118", "120", "96", "118", "1000"));

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "55", "25",
                "1.0", "3", "4", "9", "111", "102", "4", "1", "1"), candles);

        assertThat(result.status()).isEqualTo(EntryStatus.TREND_WEAKENING);
        assertThat(result.checks().latestSwingLowBelowPrevious()).isTrue();
        assertThat(result.reasons()).contains("Latest confirmed swing low is below the previous swing low");
    }

    @Test
    void shouldIgnorePartialCandleForEntryDecision() {
        List<Candle> candles = trendingCandles(26);
        candles.add(new Candle(LocalDate.of(2026, 1, 27),
                BigDecimal.valueOf(140), BigDecimal.valueOf(145), BigDecimal.valueOf(135),
                BigDecimal.valueOf(144), BigDecimal.valueOf(5000), CandleStatus.INTRADAY_PARTIAL, null));

        EntryAnalysis result = analyzer.analyze(analysis("107", "100", "105", "104", "55", "25",
                "1.0", "3", "4", "5", "111", "102", "4", "1", "1"), candles);

        assertThat(result.breakoutConfirmed()).isFalse();
    }

    private TechnicalAnalysis analysis(String lastClose,
                                       String sma21,
                                       String ema9,
                                       String ema21,
                                       String rsi,
                                       String adx,
                                       String relativeVolume,
                                       String distanceFromEma21,
                                       String pullbackDepth,
                                       String consolidationWidth,
                                       String recentHigh,
                                       String recentLow,
                                       String return5,
                                       String slope9,
                                       String slope21) {
        return new TechnicalAnalysis(
                "PETR4",
                LocalDate.of(2026, 1, 26),
                "1D",
                bd(lastClose),
                bd(ema9),
                bd(sma21),
                bd(ema9),
                bd(ema21),
                bd(rsi),
                bd("5"),
                bd(adx),
                bd("1000"),
                bd("30000000"),
                bd(relativeVolume),
                bd("2"),
                bd(return5),
                bd("8"),
                bd("1"),
                bd(distanceFromEma21),
                bd("110"),
                bd("115"),
                bd("120"),
                bd("95"),
                bd("100"),
                bd("105"),
                bd(recentHigh),
                LocalDate.of(2026, 1, 20),
                bd(recentLow),
                LocalDate.of(2026, 1, 24),
                6,
                bd(pullbackDepth),
                bd("108"),
                bd("103"),
                bd("106"),
                bd(slope9),
                bd(slope21),
                bd(consolidationWidth),
                bd("1200"),
                bd("800"),
                bd("1000"),
                bd("1000"),
                Trend.UPTREND);
    }

    private List<Candle> trendingCandles(int amount) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 1; i <= amount; i++) {
            BigDecimal close = BigDecimal.valueOf(100L + i);
            candles.add(candle(i, close.toPlainString(), close.add(BigDecimal.ONE).toPlainString(),
                    close.subtract(BigDecimal.ONE).toPlainString(), close.toPlainString(), "1000"));
        }
        return candles;
    }

    private List<Candle> breakoutReadyCandles() {
        List<Candle> candles = trendingCandles(26);
        candles.set(7, candle(8, "108", "110", "101", "108", "1000"));
        candles.set(17, candle(18, "118", "120", "110", "118", "1000"));
        return candles;
    }

    private List<Candle> slightUpConsolidationCandles(int amount) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 1; i <= amount; i++) {
            BigDecimal close = new BigDecimal("104").add(new BigDecimal("0.03").multiply(BigDecimal.valueOf(i)));
            candles.add(candle(i, close.toPlainString(), "106", "101", close.toPlainString(), "1000"));
        }
        return candles;
    }

    private List<Candle> pullbackConfirmationCandles() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            candles.add(candle(i, "103", "106", "101", "104", "1000"));
        }
        return candles;
    }

    private Candle candle(int day, String open, String high, String low, String close, String volume) {
        return new Candle(LocalDate.of(2026, 1, day), bd(open), bd(high), bd(low), bd(close), bd(volume));
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private EntryScoringConfig config() {
        return new PanicScannerEntryProperties(0, 0, 0, null, null, null, 0, null, null, null,
                null, 0, null, null, null, null, null, null, 0, null, null, null, null, 0, null).toConfig();
    }

    private EntryScoringConfig configWithVolumeRatio(String minimumConfirmationVolumeRatio) {
        return new PanicScannerEntryProperties(0, 0, 0, null, null, null, 0, null, null, null,
                null, 0, null, null, null, null, null, null, 0, null,
                new BigDecimal(minimumConfirmationVolumeRatio), null, null, 0, null).toConfig();
    }
}
