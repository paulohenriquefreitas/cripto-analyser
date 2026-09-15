package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.ScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.MomentumScannerStrategy;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerStrategy;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzeStockUseCaseTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
    private static final BigDecimal LIQUIDITY_THRESHOLD = new BigDecimal("1000000");

    private final FakeMarketDataClient marketDataClient = new FakeMarketDataClient();

    @Test
    void shouldOrchestrateMarketDataEngineAndMomentumStrategy() {
        FakeTechnicalAnalysisEngine engine = new FakeTechnicalAnalysisEngine(new TechnicalAnalysis(
                "PETR4",
                LocalDate.of(2026, 1, 21),
                "1D",
                new BigDecimal("15"),
                new BigDecimal("12"),
                new BigDecimal("10"),
                new BigDecimal("60"),
                new BigDecimal("2000000"),
                Trend.UPTREND));
        AnalyzeStockUseCase useCase = useCase(engine, momentumStrategy());
        marketDataClient.candles = candles(21, new BigDecimal("15"));

        var result = useCase.execute("petr4");

        assertThat(result.ticker()).isEqualTo("PETR4");
        assertThat(result.status()).isEqualTo(SignalStatus.QUALIFIED);
        assertThat(result.score()).isEqualTo(70);
        assertThat(result.checks()).isEqualTo(new TechnicalChecks(true, true, true, true, true, true));
        assertThat(marketDataClient.requestedTicker).isEqualTo("PETR4");
        assertThat(marketDataClient.requestedFrom).isEqualTo(LocalDate.of(2026, 5, 6));
        assertThat(marketDataClient.requestedTo).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(engine.requestedTicker).isEqualTo("PETR4");
        assertThat(engine.requestedTimeframe).isEqualTo("1D");
        assertThat(engine.requestedCandles).hasSize(21);
    }

    @Test
    void shouldRejectInsufficientCandleHistoryBeforeCalculatingIndicators() {
        ThrowingEngine engine = new ThrowingEngine();
        AnalyzeStockUseCase useCase = useCase(engine, momentumStrategy());
        marketDataClient.candles = candles(20, new BigDecimal("15"));

        var result = useCase.execute("PETR4");

        assertThat(result.status()).isEqualTo(SignalStatus.REJECTED);
        assertThat(result.score()).isZero();
        assertThat(result.analysisDate()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(result.timeframe()).isEqualTo("1D");
        assertThat(result.technicalAnalysis()).isNull();
        assertThat(result.checks()).isEqualTo(new TechnicalChecks(false, false, false, false, false, false));
        assertThat(result.reasons()).singleElement().asString().contains("Insufficient candle history");
        assertThat(engine.analyzeCalled).isFalse();
    }

    @Test
    void shouldUseOnlyClosedCandlesWhenCurrentSessionHasPartialDailyCandle() {
        Clock sessionOpenClock = Clock.fixed(
                Instant.parse("2026-08-05T15:51:00Z"),
                ZoneId.of("America/Sao_Paulo"));
        var engine = new br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter();
        AnalyzeStockUseCase useCase = new AnalyzeStockUseCase(
                marketDataClient,
                engine,
                momentumStrategy(),
                properties(),
                sessionOpenClock);
        marketDataClient.candles = candlesThroughAugustFourthWithPartial(new BigDecimal("999"), BigDecimal.valueOf(999_999));

        var first = useCase.execute("PETR4");

        marketDataClient.candles = candlesThroughAugustFourthWithPartial(new BigDecimal("1"), BigDecimal.ONE);
        var second = useCase.execute("PETR4");

        assertThat(first.analysisDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(first.lastClose()).isEqualByComparingTo("21");
        assertThat(first.currentPrice()).isEqualByComparingTo("999");
        assertThat(first.dataStatus()).isEqualTo(CandleStatus.INTRADAY_PARTIAL);
        assertThat(first.currentPartialCandle()).isNotNull();
        assertThat(first.currentPartialCandle().date()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(second.currentPrice()).isEqualByComparingTo("1");
        assertThat(second.analysisDate()).isEqualTo(first.analysisDate());
        assertThat(second.lastClose()).isEqualByComparingTo(first.lastClose());
        assertThat(second.technicalAnalysis().sma9()).isEqualByComparingTo(first.technicalAnalysis().sma9());
        assertThat(second.technicalAnalysis().sma21()).isEqualByComparingTo(first.technicalAnalysis().sma21());
        assertThat(second.technicalAnalysis().rsi9()).isEqualByComparingTo(first.technicalAnalysis().rsi9());
        assertThat(second.technicalAnalysis().averageFinancialVolume20()).isEqualByComparingTo(first.technicalAnalysis().averageFinancialVolume20());
        assertThat(second.trend()).isEqualTo(first.trend());
        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.status()).isEqualTo(first.status());
    }

    private AnalyzeStockUseCase useCase(TechnicalAnalysisEngine engine, ScannerStrategy scannerStrategy) {
        return new AnalyzeStockUseCase(
                marketDataClient,
                engine,
                scannerStrategy,
                properties(),
                FIXED_CLOCK);
    }

    private ScannerStrategy momentumStrategy() {
        return new MomentumScannerStrategy(LIQUIDITY_THRESHOLD, new ScoreCalculator());
    }

    private PanicScannerProperties properties() {
        return new PanicScannerProperties(LIQUIDITY_THRESHOLD, 90, 20, null, null, null, 0, null, null, 0);
    }

    private List<Candle> candles(int amount, BigDecimal lastClose) {
        List<Candle> candles = new ArrayList<>();
        LocalDate firstDate = LocalDate.of(2026, 1, 1);
        for (int i = 1; i <= amount; i++) {
            BigDecimal close = i == amount ? lastClose : BigDecimal.TEN;
            candles.add(new Candle(
                    firstDate.plusDays(i - 1),
                    close,
                    close,
                    close,
                    close,
                    BigDecimal.valueOf(100)));
        }
        return candles;
    }

    private List<Candle> candlesThroughAugustFourthWithPartial(BigDecimal partialClose, BigDecimal partialVolume) {
        List<Candle> candles = new ArrayList<>();
        LocalDate firstDate = LocalDate.of(2026, 7, 15);
        OffsetDateTime fetchedAt = OffsetDateTime.parse("2026-08-05T12:51:00-03:00");
        for (int i = 1; i <= 21; i++) {
            BigDecimal close = BigDecimal.valueOf(i);
            candles.add(new Candle(
                    firstDate.plusDays(i - 1),
                    close,
                    close,
                    close,
                    close,
                    BigDecimal.valueOf(100),
                    CandleStatus.CLOSED,
                    fetchedAt));
        }
        candles.add(new Candle(
                LocalDate.of(2026, 8, 5),
                partialClose,
                partialClose,
                partialClose,
                partialClose,
                partialVolume,
                CandleStatus.INTRADAY_PARTIAL,
                fetchedAt));
        return candles;
    }

    private static class FakeMarketDataClient implements MarketDataClient {

        private List<Candle> candles = List.of();
        private String requestedTicker;
        private LocalDate requestedFrom;
        private LocalDate requestedTo;

        @Override
        public List<StockSummary> listStocks() {
            return List.of();
        }

        @Override
        public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
            this.requestedTicker = ticker;
            this.requestedFrom = from;
            this.requestedTo = to;
            return candles;
        }
    }

    private static class FakeTechnicalAnalysisEngine implements TechnicalAnalysisEngine {

        private final TechnicalAnalysis analysis;
        private String requestedTicker;
        private String requestedTimeframe;
        private List<Candle> requestedCandles;

        private FakeTechnicalAnalysisEngine(TechnicalAnalysis analysis) {
            this.analysis = analysis;
        }

        @Override
        public int minimumRequiredCandles() {
            return 21;
        }

        @Override
        public TechnicalAnalysis analyze(String ticker, String timeframe, List<Candle> candles) {
            this.requestedTicker = ticker;
            this.requestedTimeframe = timeframe;
            this.requestedCandles = candles;
            return analysis;
        }
    }

    private static class ThrowingEngine implements TechnicalAnalysisEngine {

        private boolean analyzeCalled;

        @Override
        public int minimumRequiredCandles() {
            return 21;
        }

        @Override
        public TechnicalAnalysis analyze(String ticker, String timeframe, List<Candle> candles) {
            analyzeCalled = true;
            throw new AssertionError("analyze should not be called for insufficient history");
        }
    }
}
