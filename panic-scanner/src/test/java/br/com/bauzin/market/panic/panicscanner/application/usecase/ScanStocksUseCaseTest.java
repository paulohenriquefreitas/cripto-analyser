package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScanStocksUseCaseTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T02:30:00.080123456Z"),
            ZoneId.of("America/Sao_Paulo"));

    @Test
    void shouldScanMultipleSuccessfulTickers() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .with("VALE3", result("VALE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "VALE3"));

        assertThat(scan.scannedAt().toString()).isEqualTo("2026-08-04T23:30:00.080-03:00");
        assertThat(scan.strategy()).isEqualTo("MOMENTUM");
        assertThat(scan.requestedCount()).isEqualTo(2);
        assertThat(scan.successfulCount()).isEqualTo(2);
        assertThat(scan.failedCount()).isZero();
        assertThat(scan.results()).extracting(ScannerResult::ticker).containsExactly("PETR4", "VALE3");
    }

    @Test
    void shouldSortByScoreDescending() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 50, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)))
                .with("VALE3", result("VALE3", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .with("WEGE3", result("WEGE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "VALE3", "WEGE3"));

        assertThat(scan.results()).extracting(ScannerResult::ticker).containsExactly("VALE3", "WEGE3", "PETR4");
    }

    @Test
    void shouldSortTickerAscendingWhenScoresAreEqual() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("WEGE3", result("WEGE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)))
                .with("PETR4", result("PETR4", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)))
                .with("VALE3", result("VALE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("WEGE3", "PETR4", "VALE3"));

        assertThat(scan.results()).extracting(ScannerResult::ticker).containsExactly("PETR4", "VALE3", "WEGE3");
    }

    @Test
    void shouldRemoveDuplicateTickersBeforeAnalysis() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .with("VALE3", result("VALE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "petr4", "VALE3", "PETR4"));

        assertThat(analyzeStockUseCase.calls).containsExactly("PETR4", "VALE3");
        assertThat(scan.requestedCount()).isEqualTo(2);
    }

    @Test
    void shouldIsolateOneFailureAmongSuccessfulAnalyses() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .fail("INVALID3", new IllegalStateException("raw provider failure"))
                .with("VALE3", result("VALE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "INVALID3", "VALE3"));

        assertThat(scan.successfulCount()).isEqualTo(2);
        assertThat(scan.failedCount()).isEqualTo(1);
        assertThat(scan.failures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.ticker()).isEqualTo("INVALID3");
                    assertThat(failure.errorCode()).isEqualTo("UNEXPECTED_ERROR");
                    assertThat(failure.message()).isEqualTo("Unexpected error while analyzing ticker");
                });
    }

    @Test
    void shouldContinueAfterAuthenticationFailureForOneTicker() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .fail("WEGE3", new MarketDataProviderException(ProviderErrorCode.AUTHENTICATION_REQUIRED))
                .with("VALE3", result("VALE3", 75, SignalStatus.WATCH, LocalDate.of(2026, 8, 4)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "WEGE3", "VALE3"));

        assertThat(scan.results()).extracting(ScannerResult::ticker).containsExactly("PETR4", "VALE3");
        assertThat(scan.failures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.ticker()).isEqualTo("WEGE3");
                    assertThat(failure.errorCode()).isEqualTo("AUTHENTICATION_REQUIRED");
                    assertThat(failure.message()).isEqualTo("Market data provider authentication is required");
                });
    }

    @Test
    void shouldReportInsufficientHistoryAsFailureWithoutAbortingScan() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)))
                .with("WEGE3", insufficientHistory("WEGE3"));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4", "WEGE3"));

        assertThat(scan.successfulCount()).isEqualTo(1);
        assertThat(scan.failedCount()).isEqualTo(1);
        assertThat(scan.failures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.ticker()).isEqualTo("WEGE3");
                    assertThat(failure.errorCode()).isEqualTo("INSUFFICIENT_HISTORY");
                    assertThat(failure.message()).isEqualTo("Insufficient candle history for technical analysis");
                });
    }

    @Test
    void shouldNormalizeTickersBeforeDelegating() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 4)));

        new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK).execute(List.of("petr4"));

        assertThat(analyzeStockUseCase.calls).containsExactly("PETR4");
    }

    @Test
    void shouldKeepAnalysisDateFromIndividualAnalysisResult() {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase()
                .with("PETR4", result("PETR4", 100, SignalStatus.QUALIFIED, LocalDate.of(2026, 8, 2)));

        ScanExecutionResult scan = new ScanStocksUseCase(analyzeStockUseCase, FIXED_CLOCK)
                .execute(List.of("PETR4"));

        assertThat(scan.results()).singleElement()
                .extracting(ScannerResult::analysisDate)
                .isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(scan.scannedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 4));
    }

    private ScannerResult result(String ticker, int score, SignalStatus status, LocalDate analysisDate) {
        return new ScannerResult(
                ticker,
                analysisDate,
                "1D",
                new BigDecimal("42.30"),
                score,
                status,
                Trend.UPTREND,
                new TechnicalAnalysis(
                        ticker,
                        analysisDate,
                        "1D",
                        new BigDecimal("42.30"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("10.0000"),
                        new BigDecimal("60.0000"),
                        new BigDecimal("2000000.0000"),
                        Trend.UPTREND),
                new TechnicalChecks(true, true, true, true),
                List.of("Momentum result"));
    }

    private ScannerResult insufficientHistory(String ticker) {
        return new ScannerResult(
                ticker,
                LocalDate.of(2026, 8, 4),
                "1D",
                null,
                0,
                SignalStatus.REJECTED,
                null,
                null,
                new TechnicalChecks(false, false, false, false),
                List.of("Insufficient candle history: required at least 21 daily candles, got 2"));
    }

    private static class FakeAnalyzeStockUseCase extends AnalyzeStockUseCase {

        private final Map<String, ScannerResult> results = new LinkedHashMap<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        private FakeAnalyzeStockUseCase() {
            super(null, null, null, null, null);
        }

        private FakeAnalyzeStockUseCase with(String ticker, ScannerResult result) {
            results.put(ticker, result);
            return this;
        }

        private FakeAnalyzeStockUseCase fail(String ticker, RuntimeException exception) {
            failures.put(ticker, exception);
            return this;
        }

        @Override
        public ScannerResult execute(String ticker) {
            calls.add(ticker);
            if (failures.containsKey(ticker)) {
                throw failures.get(ticker);
            }
            return results.get(ticker);
        }
    }
}
