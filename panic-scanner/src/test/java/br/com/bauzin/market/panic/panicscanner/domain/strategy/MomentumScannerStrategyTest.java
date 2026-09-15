package br.com.bauzin.market.panic.panicscanner.domain.strategy;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.MomentumScoringConfig;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.ScoreCalculator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MomentumScannerStrategyTest {

    private final MomentumScannerStrategy strategy = new MomentumScannerStrategy(
            new BigDecimal("1000000"),
            new ScoreCalculator());

    @Test
    void shouldMapCurrentCandidateRuleToQualified() {
        ScannerResult result = strategy.execute(analysis(
                new BigDecimal("15"),
                new BigDecimal("12"),
                new BigDecimal("10"),
                new BigDecimal("60"),
                new BigDecimal("2000000")));

        assertThat(result.status()).isEqualTo(SignalStatus.QUALIFIED);
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.checks()).isEqualTo(new TechnicalChecks(true, true, true, true, true, true));
        assertThat(result.reasons()).containsExactly("Momentum qualified: mandatory momentum checks passed");
    }

    @Test
    void shouldMapPreviousWaitRuleToWatchWhenLiquidityIsAccepted() {
        ScannerResult result = strategy.execute(analysis(
                new BigDecimal("8"),
                new BigDecimal("9"),
                new BigDecimal("10"),
                new BigDecimal("45"),
                new BigDecimal("2000000")));

        assertThat(result.status()).isEqualTo(SignalStatus.WATCH);
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.reasons())
                .contains("Last close is not above SMA21",
                        "SMA9 is not above SMA21",
                        "RSI9 is outside the 50-70 momentum range");
    }

    @Test
    void shouldRejectWhenLiquidityIsNotAccepted() {
        ScannerResult result = strategy.execute(analysis(
                new BigDecimal("15"),
                new BigDecimal("12"),
                new BigDecimal("10"),
                new BigDecimal("60"),
                new BigDecimal("999999")));

        assertThat(result.status()).isEqualTo(SignalStatus.REJECTED);
        assertThat(result.score()).isEqualTo(69);
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("below threshold"));
    }

    @Test
    void shouldNotQualifyWhenScoreIsHighButMandatoryChecksAreNotAllAccepted() {
        MomentumScannerStrategy highScoreStrategy = new MomentumScannerStrategy(
                new BigDecimal("1000000"),
                new ScoreCalculator() {
                    @Override
                    public int calculate(TechnicalChecks checks, TechnicalAnalysis analysis, MomentumScoringConfig config) {
                        return 100;
                    }
                });

        ScannerResult result = highScoreStrategy.execute(analysis(
                new BigDecimal("8"),
                new BigDecimal("12"),
                new BigDecimal("10"),
                new BigDecimal("60"),
                new BigDecimal("2000000")));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.status()).isEqualTo(SignalStatus.WATCH);
        assertThat(result.checks().allAccepted()).isFalse();
    }

    private TechnicalAnalysis analysis(BigDecimal lastClose,
                                       BigDecimal sma9,
                                       BigDecimal sma21,
                                       BigDecimal rsi9,
                                       BigDecimal averageFinancialVolume20) {
        return new TechnicalAnalysis(
                "PETR4",
                LocalDate.of(2026, 8, 4),
                "1D",
                lastClose,
                sma9,
                sma21,
                rsi9,
                averageFinancialVolume20,
                Trend.UPTREND);
    }
}
