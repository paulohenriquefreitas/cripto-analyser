package br.com.bauzin.market.panic.panicscanner.domain.checks;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalChecksTest {

    @Test
    void shouldExposeStructuredTechnicalChecks() {
        TechnicalChecks checks = TechnicalChecks.from(
                analysis(new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("11"), new BigDecimal("60"), new BigDecimal("1000000")),
                new BigDecimal("50"),
                new BigDecimal("70"),
                new BigDecimal("1000000"));

        assertThat(checks.closeAboveSma21()).isTrue();
        assertThat(checks.sma9AboveSma21()).isTrue();
        assertThat(checks.rsiInsideRange()).isTrue();
        assertThat(checks.liquidityAccepted()).isTrue();
        assertThat(checks.allAccepted()).isTrue();
    }

    @Test
    void shouldRejectBoundaryViolations() {
        TechnicalChecks checks = TechnicalChecks.from(
                analysis(new BigDecimal("10"), new BigDecimal("9"), new BigDecimal("10"), new BigDecimal("71"), new BigDecimal("999999")),
                new BigDecimal("50"),
                new BigDecimal("70"),
                new BigDecimal("1000000"));

        assertThat(checks.closeAboveSma21()).isFalse();
        assertThat(checks.sma9AboveSma21()).isFalse();
        assertThat(checks.rsiInsideRange()).isFalse();
        assertThat(checks.liquidityAccepted()).isFalse();
        assertThat(checks.allAccepted()).isFalse();
    }

    private TechnicalAnalysis analysis(BigDecimal sma21,
                                       BigDecimal sma9,
                                       BigDecimal lastClose,
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
