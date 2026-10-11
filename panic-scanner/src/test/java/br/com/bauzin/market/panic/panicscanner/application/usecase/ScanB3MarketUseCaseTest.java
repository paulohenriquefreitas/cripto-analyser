package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ConfirmationStrength;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreBreakdown;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntrySetupType;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScanB3MarketUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T23:30:00Z"), ZoneOffset.UTC);
    private static final MarketScanCriteria CRITERIA = new MarketScanCriteria(new BigDecimal("20000000"), 70, 20);

    private final FakeMarketDataClient marketDataClient = new FakeMarketDataClient();
    private final FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase();
    private final ScanB3MarketUseCase useCase = new ScanB3MarketUseCase(marketDataClient, analyzeStockUseCase, CLOCK);

    @Test
    void shouldDiscoverDeduplicateAndFilterUnsupportedAssetTypes() {
        marketDataClient.stocks = List.of(
                new StockSummary("petr4", "Petrobras PN", "stock", true),
                new StockSummary("PETR4", "Petrobras PN duplicate", "stock", true),
                new StockSummary("BOVA11", "ETF", "ETF", true),
                new StockSummary("HGLG11", "FII", "FII", true),
                new StockSummary("AAPL34", "BDR", "BDR", true),
                new StockSummary("VALE3", "Inactive", "stock", false));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 90, "1.5", "25"));

        MarketScanExecutionResult result = useCase.execute(CRITERIA);

        assertThat(result.discoveredCount()).isEqualTo(5);
        assertThat(result.eligibleCount()).isEqualTo(1);
        assertThat(result.analyzedCount()).isEqualTo(1);
        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("PETR4");
        assertThat(analyzeStockUseCase.requestedTickers).containsExactly("PETR4");
    }

    @Test
    void shouldSortQualifiedResultsDeterministicallyAndApplyLimit() {
        marketDataClient.stocks = List.of(
                new StockSummary("VALE3", "Vale", "stock", true),
                new StockSummary("PETR4", "Petrobras", "stock", true),
                new StockSummary("WEGE3", "Weg", "stock", true),
                new StockSummary("ABCD3", "Tie", "stock", true));
        analyzeStockUseCase.results.put("VALE3", result("VALE3", 95, "1.1", "22"));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 95, "1.8", "21"));
        analyzeStockUseCase.results.put("WEGE3", result("WEGE3", 90, "2.5", "40"));
        analyzeStockUseCase.results.put("ABCD3", result("ABCD3", 95, "1.8", "21"));

        MarketScanExecutionResult result = useCase.execute(new MarketScanCriteria(new BigDecimal("20000000"), 70, 3));

        assertThat(result.qualifiedCount()).isEqualTo(4);
        assertThat(result.returnedCount()).isEqualTo(3);
        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("ABCD3", "PETR4", "VALE3");
    }

    @Test
    void shouldContinueWhenOneTickerFails() {
        marketDataClient.stocks = List.of(
                new StockSummary("PETR4", "Petrobras", "stock", true),
                new StockSummary("WEGE3", "Weg", "stock", true),
                new StockSummary("VALE3", "Vale", "stock", true));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 90, "1.4", "25"));
        analyzeStockUseCase.failures.put("WEGE3", new MarketDataProviderException(ProviderErrorCode.TICKER_NOT_FOUND));
        analyzeStockUseCase.results.put("VALE3", result("VALE3", 88, "1.3", "24"));

        MarketScanExecutionResult result = useCase.execute(CRITERIA);

        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("PETR4", "VALE3");
        assertThat(result.failures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.ticker()).isEqualTo("WEGE3");
                    assertThat(failure.errorCode()).isEqualTo("TICKER_NOT_FOUND");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "AUTHENTICATION_REQUIRED,false", "AUTHENTICATION_REQUIRED,true",
            "RATE_LIMIT_EXCEEDED,false", "RATE_LIMIT_EXCEEDED,true",
            "PROVIDER_UNAVAILABLE,false", "PROVIDER_UNAVAILABLE,true"
    })
    void shouldContinueAfterProviderFailure(ProviderErrorCode errorCode, boolean experimental) {
        marketDataClient.stocks = List.of(
                new StockSummary("PETR4", "Petrobras", "stock", true),
                new StockSummary("WEGE3", "Weg", "stock", true),
                new StockSummary("VALE3", "Vale", "stock", true));
        analyzeStockUseCase.failures.put("PETR4", new MarketDataProviderException(errorCode));
        analyzeStockUseCase.failures.put("WEGE3", new MarketDataProviderException(errorCode));
        analyzeStockUseCase.results.put("VALE3", result("VALE3", 88, "1.3", "24")
                .withExperimentalEntry(new br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis(
                        "ANALYZE_ENTRY", List.of("EARLY_REVERSAL"), Map.of(), List.of())));

        MarketScanExecutionResult result = experimental
                ? useCase.executeExperimental(CRITERIA) : useCase.execute(CRITERIA);

        assertThat(result.analyzedCount()).isEqualTo(3);
        assertThat(analyzeStockUseCase.requestedTickers).containsExactly("PETR4", "WEGE3", "VALE3");
        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("VALE3");
        assertThat(result.failures()).extracting(ScanFailure::ticker).containsExactly("PETR4", "WEGE3");
        assertThat(result.failures()).extracting(ScanFailure::errorCode)
                .containsExactly(errorCode.name(), errorCode.name());
    }

    @Test
    void shouldReturnEmptyResultWhenDiscoveryIsEmpty() {
        marketDataClient.stocks = List.of();

        MarketScanExecutionResult result = useCase.execute(CRITERIA);

        assertThat(result.discoveredCount()).isZero();
        assertThat(result.eligibleCount()).isZero();
        assertThat(result.analyzedCount()).isZero();
        assertThat(result.providerRequestCount()).isEqualTo(1);
        assertThat(result.results()).isEmpty();
    }

    @Test
    void shouldRankByEntryScoreWhenEntryRankingModeIsRequested() {
        marketDataClient.stocks = List.of(
                new StockSummary("PETR4", "Petrobras", "stock", true),
                new StockSummary("VALE3", "Vale", "stock", true));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 95, "1.4", "25")
                .withEntryAnalysis(entry(EntryStatus.WAIT_BREAKOUT, 60)));
        analyzeStockUseCase.results.put("VALE3", result("VALE3", 80, "1.2", "22")
                .withEntryAnalysis(entry(EntryStatus.ENTRY_READY, 90)));

        MarketScanExecutionResult result = useCase.execute(new MarketScanCriteria(
                new BigDecimal("20000000"),
                70,
                70,
                List.of(EntryStatus.ENTRY_READY, EntryStatus.WAIT_BREAKOUT),
                RankingMode.ENTRY,
                20));

        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("VALE3");
    }

    @Test
    void shouldKeepQualifiedOnlyBehaviorForMomentumRanking() {
        marketDataClient.stocks = List.of(
                new StockSummary("RADL3", "Raia Drogasil", "stock", true),
                new StockSummary("PETR4", "Petrobras", "stock", true));
        analyzeStockUseCase.results.put("RADL3", result("RADL3", 65, "1.8", "16", SignalStatus.WATCH)
                .withEntryAnalysis(entry(EntryStatus.BREAKOUT_READY, 88)));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 90, "1.4", "25", SignalStatus.QUALIFIED)
                .withEntryAnalysis(entry(EntryStatus.WATCH, 40)));

        MarketScanExecutionResult result = useCase.execute(new MarketScanCriteria(
                new BigDecimal("20000000"),
                70,
                0,
                List.of(),
                RankingMode.MOMENTUM,
                20));

        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("PETR4");
        assertThat(result.momentumQualifiedCount()).isEqualTo(1);
        assertThat(result.momentumWatchCount()).isEqualTo(1);
        assertThat(result.breakoutReadyCount()).isEqualTo(1);
    }

    @Test
    void shouldIncludeWatchMomentumBreakoutReadyWhenEntryRankingIsRequested() {
        marketDataClient.stocks = List.of(
                new StockSummary("RADL3", "Raia Drogasil", "stock", true),
                new StockSummary("PETR4", "Petrobras", "stock", true));
        analyzeStockUseCase.results.put("RADL3", result("RADL3", 65, "1.8", "16", SignalStatus.WATCH)
                .withEntryAnalysis(entry(EntryStatus.BREAKOUT_READY, 88)));
        analyzeStockUseCase.results.put("PETR4", result("PETR4", 90, "1.4", "25", SignalStatus.QUALIFIED)
                .withEntryAnalysis(entry(EntryStatus.PULLBACK_READY, 80)));

        MarketScanExecutionResult result = useCase.execute(new MarketScanCriteria(
                new BigDecimal("20000000"),
                70,
                75,
                List.of(),
                RankingMode.ENTRY,
                20));

        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("RADL3", "PETR4");
        assertThat(result.breakoutEligibleCount()).isEqualTo(2);
        assertThat(result.breakoutReadyCount()).isEqualTo(1);
        assertThat(result.pullbackReadyCount()).isEqualTo(1);
    }

    @Test
    void shouldRankBreakoutModeByEntryScoreDeterministically() {
        marketDataClient.stocks = List.of(
                new StockSummary("RADL3", "Raia Drogasil", "stock", true),
                new StockSummary("ABCD3", "Tie", "stock", true),
                new StockSummary("VALE3", "Vale", "stock", true));
        analyzeStockUseCase.results.put("RADL3", result("RADL3", 65, "1.8", "16", SignalStatus.WATCH)
                .withEntryAnalysis(entry(EntryStatus.BREAKOUT_READY, 88)));
        analyzeStockUseCase.results.put("ABCD3", result("ABCD3", 64, "2.0", "15", SignalStatus.WATCH)
                .withEntryAnalysis(entry(EntryStatus.BREAKOUT_READY, 88)));
        analyzeStockUseCase.results.put("VALE3", result("VALE3", 90, "1.3", "22", SignalStatus.QUALIFIED)
                .withEntryAnalysis(entry(EntryStatus.WAIT_BREAKOUT, 55)));

        MarketScanExecutionResult result = useCase.execute(new MarketScanCriteria(
                new BigDecimal("20000000"),
                70,
                0,
                List.of(),
                RankingMode.BREAKOUT,
                20));

        assertThat(result.results()).extracting(ScannerResult::ticker).containsExactly("ABCD3", "RADL3", "VALE3");
    }

    @Test
    void experimentalScanIncludesWatchWithoutChangingProductionSelection() {
        marketDataClient.stocks = List.of(new StockSummary("TEST3", "Test", "stock", true));
        var watch = result("TEST3", 50, "1.5", "18", SignalStatus.WATCH)
                .withExperimentalEntry(new br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis(
                        "ANALYZE_ENTRY", List.of("ADX_RECOVERY"), Map.of("direction", true), List.of("test")));
        analyzeStockUseCase.results.put("TEST3", watch);
        assertThat(useCase.execute(CRITERIA).results()).isEmpty();
        assertThat(useCase.executeExperimental(CRITERIA).results()).containsExactly(watch);
        assertThat(useCase.execute(CRITERIA).results()).isEmpty();
        analyzeStockUseCase.results.put("TEST3", watch.withExperimentalEntry(null));
        assertThat(useCase.executeExperimental(CRITERIA).results()).isEmpty();
    }

    private ScannerResult result(String ticker, int score, String relativeVolume20, String adx14) {
        return result(ticker, score, relativeVolume20, adx14, SignalStatus.QUALIFIED);
    }

    private ScannerResult result(String ticker, int score, String relativeVolume20, String adx14, SignalStatus status) {
        TechnicalAnalysis analysis = new TechnicalAnalysis(
                ticker,
                LocalDate.of(2026, 8, 4),
                "1D",
                new BigDecimal("42"),
                new BigDecimal("41"),
                new BigDecimal("40"),
                new BigDecimal("41"),
                new BigDecimal("40"),
                new BigDecimal("60"),
                BigDecimal.ONE,
                new BigDecimal(adx14),
                BigDecimal.valueOf(1000),
                new BigDecimal("30000000"),
                new BigDecimal(relativeVolume20),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("43"),
                new BigDecimal("35"),
                Trend.UPTREND);
        return new ScannerResult(
                ticker,
                LocalDate.of(2026, 8, 4),
                "1D",
                new BigDecimal("42"),
                score,
                status,
                Trend.UPTREND,
                analysis,
                new TechnicalChecks(true, true, true, true, true, true),
                List.of("Momentum qualified: mandatory momentum checks passed"));
    }

    private EntryAnalysis entry(EntryStatus status, int score) {
        EntryScoreBreakdown breakdown = new EntryScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, score);
        return new EntryAnalysis(
                status,
                status == EntryStatus.BREAKOUT_READY ? EntrySetupType.BREAKOUT
                        : status == EntryStatus.PULLBACK_READY || status == EntryStatus.ENTRY_READY ? EntrySetupType.PULLBACK : EntrySetupType.NONE,
                ConfirmationStrength.MODERATE,
                score,
                breakdown,
                new EntryChecks(true, true, true, true, true, true, true, true, true,
                        true, true, true, true, true, true, false, true, true, true, true,
                        true, true, true, true, false, false, false, false, false, false,
                        false, true, true),
                List.of("entry test"),
                new BigDecimal("42"),
                new BigDecimal("38"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                2,
                BigDecimal.TEN,
                new BigDecimal("38"),
                new BigDecimal("42"),
                new BigDecimal("41"),
                BigDecimal.ONE,
                false,
                true,
                status == EntryStatus.ENTRY_READY,
                false,
                false);
    }

    private static class FakeMarketDataClient implements MarketDataClient {

        private List<StockSummary> stocks = List.of();

        @Override
        public List<StockSummary> listStocks() {
            return stocks;
        }

        @Override
        public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
            return List.of();
        }
    }

    private static class FakeAnalyzeStockUseCase extends AnalyzeStockUseCase {

        private final Map<String, ScannerResult> results = new LinkedHashMap<>();
        private final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        private final List<String> requestedTickers = new java.util.ArrayList<>();

        private FakeAnalyzeStockUseCase() {
            super(null, null, null, null, null);
        }

        @Override
        public ScannerResult executeExperimental(String ticker) {
            return execute(ticker);
        }

        @Override
        public ScannerResult execute(String ticker) {
            requestedTickers.add(ticker);
            RuntimeException failure = failures.get(ticker);
            if (failure != null) {
                throw failure;
            }
            return results.get(ticker);
        }
    }
}
