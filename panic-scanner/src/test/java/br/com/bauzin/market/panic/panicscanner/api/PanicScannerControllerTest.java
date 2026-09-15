package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.usecase.AnalyzeStockUseCase;
import br.com.bauzin.market.panic.panicscanner.application.usecase.MarketScanCriteria;
import br.com.bauzin.market.panic.panicscanner.application.usecase.MarketScanExecutionResult;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanB3MarketUseCase;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanExecutionResult;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanFailure;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanStocksUseCase;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PanicScannerControllerTest {

    @Test
    void shouldReturnScannerResult() throws Exception {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase(new ScannerResult(
                "PETR4",
                LocalDate.of(2026, 8, 4),
                "1D",
                new BigDecimal("42.30"),
                100,
                SignalStatus.QUALIFIED,
                Trend.UPTREND,
                new TechnicalAnalysis(
                        "PETR4",
                        LocalDate.of(2026, 8, 4),
                        "1D",
                        new BigDecimal("42.30"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("10.0000"),
                        new BigDecimal("60.0000"),
                        new BigDecimal("2000000.0000"),
                        Trend.UPTREND),
                new TechnicalChecks(true, true, true, true),
                List.of("Momentum qualified")));
        MockMvc mockMvc = mockMvc(analyzeStockUseCase);

        mockMvc.perform(post("/api/panic-scanner/analyze/PETR4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("PETR4"))
                .andExpect(jsonPath("$.analysisDate").value("2026-08-04"))
                .andExpect(jsonPath("$.timeframe").value("1D"))
                .andExpect(jsonPath("$.lastClose").value(42.30))
                .andExpect(jsonPath("$.score").value(100))
                .andExpect(jsonPath("$.status").value("QUALIFIED"))
                .andExpect(jsonPath("$.checks.closeAboveSma21").value(true))
                .andExpect(jsonPath("$.technicalAnalysis.sma9").value(12.0000));
    }

    @Test
    void shouldReturnBadRequestForInvalidTicker() throws Exception {
        FakeAnalyzeStockUseCase analyzeStockUseCase = new FakeAnalyzeStockUseCase(null);
        MockMvc mockMvc = mockMvc(analyzeStockUseCase);

        mockMvc.perform(post("/api/panic-scanner/analyze/BTCUSDT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").exists());

        org.assertj.core.api.Assertions.assertThat(analyzeStockUseCase.called).isFalse();
    }

    @Test
    void shouldReturnScanResponse() throws Exception {
        ScannerResult result = result("PETR4", 100);
        FakeScanStocksUseCase scanStocksUseCase = new FakeScanStocksUseCase(new ScanExecutionResult(
                OffsetDateTime.parse("2026-08-04T23:30:00-03:00"),
                "MOMENTUM",
                1,
                1,
                0,
                List.of(result),
                List.of()));
        MockMvc mockMvc = mockMvc(new FakeAnalyzeStockUseCase(null), scanStocksUseCase, properties(3));

        mockMvc.perform(post("/api/panic-scanner/scan")
                        .contentType("application/json")
                        .content("{\"tickers\":[\"PETR4\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("MOMENTUM"))
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.successfulCount").value(1))
                .andExpect(jsonPath("$.results[0].ticker").value("PETR4"));
    }

    @Test
    void shouldReturnBadRequestForEmptyScanRequest() throws Exception {
        MockMvc mockMvc = mockMvc(new FakeAnalyzeStockUseCase(null), new FakeScanStocksUseCase(null), properties(3));

        mockMvc.perform(post("/api/panic-scanner/scan")
                        .contentType("application/json")
                        .content("{\"tickers\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").exists());
    }

    @Test
    void shouldReturnBadRequestWhenScanRequestExceedsMaximum() throws Exception {
        MockMvc mockMvc = mockMvc(new FakeAnalyzeStockUseCase(null), new FakeScanStocksUseCase(null), properties(2));

        mockMvc.perform(post("/api/panic-scanner/scan")
                        .contentType("application/json")
                        .content("{\"tickers\":[\"PETR4\",\"VALE3\",\"WEGE3\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("ticker list cannot contain more than 2 items"));
    }

    @Test
    void shouldReturnBadRequestForInvalidScanTicker() throws Exception {
        MockMvc mockMvc = mockMvc(new FakeAnalyzeStockUseCase(null), new FakeScanStocksUseCase(null), properties(3));

        mockMvc.perform(post("/api/panic-scanner/scan")
                        .contentType("application/json")
                        .content("{\"tickers\":[\"PETR4\",\"BTCUSDT\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").exists());
    }

    @Test
    void shouldReturnMarketScanResponseWithDefaults() throws Exception {
        ScannerResult result = result("PETR4", 88);
        FakeScanB3MarketUseCase scanB3MarketUseCase = new FakeScanB3MarketUseCase(new MarketScanExecutionResult(
                OffsetDateTime.parse("2026-08-04T23:30:00-03:00"),
                "MOMENTUM",
                3,
                2,
                2,
                1,
                1,
                1,
                1,
                0,
                0,
                1,
                1500,
                750,
                3,
                List.of(result),
                List.of(new ScanFailure("WEGE3", "AUTHENTICATION_REQUIRED", "Market data provider authentication is required"))));
        MockMvc mockMvc = mockMvc(
                new FakeAnalyzeStockUseCase(null),
                new FakeScanStocksUseCase(null),
                scanB3MarketUseCase,
                properties(3));

        mockMvc.perform(post("/api/panic-scanner/market-scan")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("MOMENTUM"))
                .andExpect(jsonPath("$.discoveredCount").value(3))
                .andExpect(jsonPath("$.eligibleCount").value(2))
                .andExpect(jsonPath("$.providerRequestCount").value(3))
                .andExpect(jsonPath("$.results[0].ticker").value("PETR4"))
                .andExpect(jsonPath("$.failures[0].errorCode").value("AUTHENTICATION_REQUIRED"));

        org.assertj.core.api.Assertions.assertThat(scanB3MarketUseCase.criteria.limit()).isEqualTo(50);
        org.assertj.core.api.Assertions.assertThat(scanB3MarketUseCase.criteria.minimumScore()).isEqualTo(70);
    }

    @Test
    void shouldReturnBadRequestWhenMarketScanLimitExceedsMaximum() throws Exception {
        MockMvc mockMvc = mockMvc(
                new FakeAnalyzeStockUseCase(null),
                new FakeScanStocksUseCase(null),
                new FakeScanB3MarketUseCase(null),
                properties(3));

        mockMvc.perform(post("/api/panic-scanner/market-scan")
                        .contentType("application/json")
                        .content("{\"limit\":51}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("limit must be between 1 and 50"));
    }

    private MockMvc mockMvc(AnalyzeStockUseCase analyzeStockUseCase) {
        return mockMvc(analyzeStockUseCase, new FakeScanStocksUseCase(null), new FakeScanB3MarketUseCase(null), properties(20));
    }

    private MockMvc mockMvc(AnalyzeStockUseCase analyzeStockUseCase,
                            ScanStocksUseCase scanStocksUseCase,
                            PanicScannerProperties properties) {
        return mockMvc(analyzeStockUseCase, scanStocksUseCase, new FakeScanB3MarketUseCase(null), properties);
    }

    private MockMvc mockMvc(AnalyzeStockUseCase analyzeStockUseCase,
                            ScanStocksUseCase scanStocksUseCase,
                            ScanB3MarketUseCase scanB3MarketUseCase,
                            PanicScannerProperties properties) {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders
                .standaloneSetup(new PanicScannerController(analyzeStockUseCase, scanStocksUseCase, scanB3MarketUseCase, properties))
                .setControllerAdvice(new PanicScannerExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private PanicScannerProperties properties(int maxTickersPerScan) {
        return new PanicScannerProperties(new BigDecimal("1000000"), 90, maxTickersPerScan, null, null, null, 0, null, null, 0);
    }

    private ScannerResult result(String ticker, int score) {
        return new ScannerResult(
                ticker,
                LocalDate.of(2026, 8, 4),
                "1D",
                new BigDecimal("42.30"),
                score,
                SignalStatus.QUALIFIED,
                Trend.UPTREND,
                new TechnicalAnalysis(
                        ticker,
                        LocalDate.of(2026, 8, 4),
                        "1D",
                        new BigDecimal("42.30"),
                        new BigDecimal("12.0000"),
                        new BigDecimal("10.0000"),
                        new BigDecimal("60.0000"),
                        new BigDecimal("2000000.0000"),
                        Trend.UPTREND),
                new TechnicalChecks(true, true, true, true),
                List.of("Momentum qualified"));
    }

    private static class FakeAnalyzeStockUseCase extends AnalyzeStockUseCase {

        private final ScannerResult result;
        private boolean called;

        private FakeAnalyzeStockUseCase(ScannerResult result) {
            super(null, null, null, null, null);
            this.result = result;
        }

        @Override
        public ScannerResult execute(String ticker) {
            called = true;
            return result;
        }
    }

    private static class FakeScanStocksUseCase extends ScanStocksUseCase {

        private final ScanExecutionResult result;

        private FakeScanStocksUseCase(ScanExecutionResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public ScanExecutionResult execute(List<String> tickers) {
            return result;
        }
    }

    private static class FakeScanB3MarketUseCase extends ScanB3MarketUseCase {

        private final MarketScanExecutionResult result;
        private MarketScanCriteria criteria;

        private FakeScanB3MarketUseCase(MarketScanExecutionResult result) {
            super(null, null, null);
            this.result = result;
        }

        @Override
        public MarketScanExecutionResult execute(MarketScanCriteria criteria) {
            this.criteria = criteria;
            return result;
        }
    }
}
