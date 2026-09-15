package br.com.bauzin.market.panic.panicscanner.domain.scoring;

import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreCalculatorTest {

    private final ScoreCalculator scoreCalculator = new ScoreCalculator();

    @Test
    void shouldCalculateExplicitMomentumScore() {
        TechnicalChecks checks = new TechnicalChecks(true, true, true, true, true, true);
        TechnicalAnalysis analysis = analysis(
                new BigDecimal("25"),
                new BigDecimal("20000000"),
                new BigDecimal("1.2"),
                new BigDecimal("2"),
                new BigDecimal("3"));
        MomentumScoringConfig config = new MomentumScoringConfig(
                new BigDecimal("20000000"),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(70),
                BigDecimal.valueOf(20),
                70,
                BigDecimal.valueOf(8),
                new BigDecimal("1.2"));

        assertThat(scoreCalculator.calculate(checks, analysis, config)).isEqualTo(97);
    }

    @Test
    void shouldNotLetScoreOverrideMandatoryChecks() {
        TechnicalChecks checks = new TechnicalChecks(false, true, true, true, true, true);
        TechnicalAnalysis analysis = analysis(
                new BigDecimal("40"),
                new BigDecimal("40000000"),
                new BigDecimal("2.4"),
                BigDecimal.ZERO,
                BigDecimal.TEN);
        MomentumScoringConfig config = new MomentumScoringConfig(
                new BigDecimal("20000000"),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(70),
                BigDecimal.valueOf(20),
                70,
                BigDecimal.valueOf(8),
                new BigDecimal("1.2"));

        assertThat(scoreCalculator.calculate(checks, analysis, config)).isGreaterThanOrEqualTo(70);
        assertThat(checks.allAccepted()).isFalse();
    }

    private TechnicalAnalysis analysis(BigDecimal adx14,
                                       BigDecimal averageFinancialVolume20,
                                       BigDecimal relativeVolume20,
                                       BigDecimal distanceFromSma21Percent,
                                       BigDecimal return20DaysPercent) {
        return new TechnicalAnalysis(
                "PETR4",
                LocalDate.of(2026, 8, 4),
                "1D",
                new BigDecimal("42"),
                new BigDecimal("41"),
                new BigDecimal("40"),
                new BigDecimal("41"),
                new BigDecimal("40"),
                new BigDecimal("60"),
                BigDecimal.ONE,
                adx14,
                BigDecimal.valueOf(1000),
                averageFinancialVolume20,
                relativeVolume20,
                distanceFromSma21Percent,
                BigDecimal.ONE,
                return20DaysPercent,
                new BigDecimal("43"),
                new BigDecimal("35"),
                Trend.UPTREND);
    }
}
