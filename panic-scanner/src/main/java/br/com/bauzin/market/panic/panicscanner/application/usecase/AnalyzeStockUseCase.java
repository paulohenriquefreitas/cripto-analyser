package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.entry.EntryAnalyzer;
import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ConfirmationStrength;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntrySetupType;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerStrategy;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Orchestrates one-stock analysis using market data, technical analysis and strategy ports. */
@Service
public class AnalyzeStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeStockUseCase.class);
    private static final String DAILY_TIMEFRAME = "1D";

    private final MarketDataClient marketDataClient;
    private final TechnicalAnalysisEngine technicalAnalysisEngine;
    private final ScannerStrategy scannerStrategy;
    private final EntryAnalyzer entryAnalyzer;
    private final PanicScannerProperties properties;
    private final Clock clock;

    public AnalyzeStockUseCase(MarketDataClient marketDataClient,
                               TechnicalAnalysisEngine technicalAnalysisEngine,
                               ScannerStrategy scannerStrategy,
                               PanicScannerProperties properties,
                               Clock clock) {
        this(marketDataClient, technicalAnalysisEngine, scannerStrategy, null, properties, clock);
    }

    @Autowired
    public AnalyzeStockUseCase(MarketDataClient marketDataClient,
                               TechnicalAnalysisEngine technicalAnalysisEngine,
                               ScannerStrategy scannerStrategy,
                               EntryAnalyzer entryAnalyzer,
                               PanicScannerProperties properties,
                               Clock clock) {
        this.marketDataClient = marketDataClient;
        this.technicalAnalysisEngine = technicalAnalysisEngine;
        this.scannerStrategy = scannerStrategy;
        this.entryAnalyzer = entryAnalyzer;
        this.properties = properties;
        this.clock = clock;
    }

    public ScannerResult execute(String ticker) {
        String normalizedTicker = ticker.trim().toUpperCase(Locale.ROOT);
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(properties.lookbackDays());
        List<Candle> candles = marketDataClient.getDailyCandles(normalizedTicker, from, to);
        List<Candle> closedCandles = candles.stream()
                .filter(candle -> candle.status() == CandleStatus.CLOSED)
                .sorted(Comparator.comparing(Candle::date))
                .toList();
        Candle currentPartialCandle = currentPartialCandle(candles);
        LocalDate selectedAnalysisDate = closedCandles.isEmpty() ? null : closedCandles.get(closedCandles.size() - 1).date();

        log.debug(
                "panic-scanner candles ticker={} providerCandleCount={} closedCandleCount={} partialCandleDate={} analysisDate={}",
                normalizedTicker,
                candles.size(),
                closedCandles.size(),
                currentPartialCandle == null ? null : currentPartialCandle.date(),
                selectedAnalysisDate);

        if (closedCandles.size() < technicalAnalysisEngine.minimumRequiredCandles()) {
            return new ScannerResult(
                    normalizedTicker,
                    selectedAnalysisDate,
                    DAILY_TIMEFRAME,
                    null,
                    currentPrice(candles),
                    dataStatus(currentPartialCandle),
                    currentPartialCandle,
                    marketDataUpdatedAt(candles),
                    0,
                    SignalStatus.REJECTED,
                    null,
                    null,
                    null,
                    new TechnicalChecks(false, false, false, false, false, false),
                    List.of("Insufficient candle history: required at least "
                            + technicalAnalysisEngine.minimumRequiredCandles()
                            + " closed daily candles, got " + closedCandles.size()));
        }

        TechnicalAnalysis analysis = technicalAnalysisEngine.analyze(normalizedTicker, DAILY_TIMEFRAME, candles);
        ScannerResult result = scannerStrategy.execute(analysis);
        if (entryAnalyzer != null) {
            result = result.withEntryAnalysis(entryAnalyzer.analyze(analysis, closedCandles));
            result = withPotentialIntradayBreakout(result, currentPartialCandle, analysis);
        }
        return result
                .withMarketDataSnapshot(
                        currentPrice(candles),
                        dataStatus(currentPartialCandle),
                        currentPartialCandle,
                marketDataUpdatedAt(candles));
    }

    private ScannerResult withPotentialIntradayBreakout(ScannerResult result,
                                                        Candle currentPartialCandle,
                                                        TechnicalAnalysis analysis) {
        if (currentPartialCandle == null
                || result.entryAnalysis() == null
                || result.entryAnalysis().status() == EntryStatus.BREAKOUT_READY
                || currentPartialCandle.close().compareTo(analysis.highestHigh20()) <= 0
                || currentPartialCandle.close().compareTo(analysis.ema9()) <= 0) {
            return result;
        }
        EntryAnalysis entry = result.entryAnalysis();
        EntryAnalysis potential = new EntryAnalysis(
                EntryStatus.POTENTIAL_BREAKOUT,
                EntrySetupType.BREAKOUT,
                ConfirmationStrength.NONE,
                entry.score(),
                entry.scoreBreakdown(),
                entry.checks(),
                java.util.stream.Stream.concat(
                                entry.reasons().stream(),
                                java.util.stream.Stream.of("Intraday candle is breaking above resistance but is not closed"))
                        .toList(),
                entry.recentHigh(),
                entry.recentLow(),
                entry.pullbackDepthPercent(),
                entry.distanceFromEma9Percent(),
                entry.distanceFromEma21Percent(),
                entry.candlesSinceRecentHigh(),
                entry.consolidationWidthPercent(),
                entry.nearestSupport(),
                entry.nearestResistance(),
                entry.recentHighBeforeLatest(),
                entry.breakoutPercentAboveResistance(),
                false,
                entry.pullbackDetected(),
                entry.pullbackConfirmed(),
                entry.overextended(),
                entry.sideways());
        return result.withEntryAnalysis(potential);
    }

    private Candle currentPartialCandle(List<Candle> candles) {
        return candles.stream()
                .filter(candle -> candle.status() == CandleStatus.INTRADAY_PARTIAL)
                .max(Comparator.comparing(Candle::date))
                .orElse(null);
    }

    private CandleStatus dataStatus(Candle currentPartialCandle) {
        return currentPartialCandle == null ? CandleStatus.CLOSED : CandleStatus.INTRADAY_PARTIAL;
    }

    private BigDecimal currentPrice(List<Candle> candles) {
        return candles.stream()
                .max(Comparator.comparing(Candle::date))
                .map(Candle::close)
                .orElse(null);
    }

    private OffsetDateTime marketDataUpdatedAt(List<Candle> candles) {
        return candles.stream()
                .map(Candle::marketDataUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> OffsetDateTime.now(clock));
    }
}
