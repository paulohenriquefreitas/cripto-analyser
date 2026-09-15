package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sequential MVP scan of the B3 equity universe returned by the configured provider. */
@Service
public class ScanB3MarketUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScanB3MarketUseCase.class);
    private static final java.util.regex.Pattern B3_EQUITY_TICKER = java.util.regex.Pattern.compile("^[A-Z]{4}[0-9]{1,2}$");
    private static final List<String> UNSUPPORTED_TYPES = List.of("ETF", "FII", "BDR", "FUND", "FUNDO");
    private static final List<String> SUPPORTED_TYPES = List.of("STOCK", "EQUITY", "AÇÃO", "ACAO");

    private final MarketDataClient marketDataClient;
    private final AnalyzeStockUseCase analyzeStockUseCase;
    private final Clock clock;

    public ScanB3MarketUseCase(MarketDataClient marketDataClient, AnalyzeStockUseCase analyzeStockUseCase, Clock clock) {
        this.marketDataClient = marketDataClient;
        this.analyzeStockUseCase = analyzeStockUseCase;
        this.clock = clock;
    }

    public MarketScanExecutionResult execute(MarketScanCriteria criteria) {
        Instant startedAt = clock.instant();
        List<ScanFailure> failures = new ArrayList<>();
        List<StockSummary> discoveredStocks;
        try {
            discoveredStocks = marketDataClient.listStocks();
        } catch (MarketDataProviderException exception) {
            failures.add(failure("MARKET", exception.errorCode()));
            return result(startedAt, 0, 0, 0, List.of(), failures);
        }

        List<StockSummary> uniqueStocks = deduplicate(discoveredStocks);
        List<String> eligibleTickers = uniqueStocks.stream()
                .filter(this::isEligibleEquity)
                .map(stock -> stock.ticker().trim().toUpperCase(Locale.ROOT))
                .toList();

        List<ScannerResult> successful = new ArrayList<>();
        int analyzedCount = 0;
        boolean providerWideStop = false;
        for (String ticker : eligibleTickers) {
            if (providerWideStop) {
                break;
            }
            analyzedCount++;
            try {
                ScannerResult result = analyzeStockUseCase.execute(ticker);
                if (isAnalysisFailure(result)) {
                    failures.add(failure(ticker, ProviderErrorCode.INSUFFICIENT_HISTORY));
                    log.info("panic-scanner market-scan ticker={} status={} reason={}",
                            ticker, SignalStatus.REJECTED, ProviderErrorCode.INSUFFICIENT_HISTORY);
                } else {
                    successful.add(result);
                    log.info("panic-scanner market-scan ticker={} status={} score={}",
                            ticker, result.status(), result.score());
                }
            } catch (MarketDataProviderException exception) {
                failures.add(failure(ticker, exception.errorCode()));
                log.info("panic-scanner market-scan ticker={} status={} errorCode={}",
                        ticker, SignalStatus.REJECTED, exception.errorCode());
                providerWideStop = isProviderWideFailure(exception.errorCode());
            } catch (RuntimeException exception) {
                failures.add(failure(ticker, ProviderErrorCode.UNEXPECTED_ERROR));
                log.info("panic-scanner market-scan ticker={} status={} errorCode={}",
                        ticker, SignalStatus.REJECTED, ProviderErrorCode.UNEXPECTED_ERROR);
            }
        }

        int momentumQualifiedCount = (int) successful.stream()
                .filter(result -> result.status() == SignalStatus.QUALIFIED)
                .count();
        int momentumWatchCount = (int) successful.stream()
                .filter(result -> result.status() == SignalStatus.WATCH)
                .count();
        int breakoutEligibleCount = (int) successful.stream()
                .filter(this::breakoutEligible)
                .count();
        int breakoutReadyCount = (int) successful.stream()
                .filter(result -> entryStatus(result) == EntryStatus.BREAKOUT_READY)
                .count();
        int pullbackReadyCount = (int) successful.stream()
                .filter(result -> entryStatus(result) == EntryStatus.PULLBACK_READY
                        || entryStatus(result) == EntryStatus.PULLBACK_CONFIRMED
                        || entryStatus(result) == EntryStatus.ENTRY_READY)
                .count();

        List<ScannerResult> candidates = successful.stream()
                .filter(result -> liquidityAccepted(result, criteria))
                .filter(result -> rankingModeAccepted(result, criteria))
                .filter(result -> entryStatusAccepted(result, criteria))
                .sorted(resultOrdering(criteria.rankingMode()))
                .toList();

        List<ScannerResult> returned = candidates.stream()
                .limit(criteria.limit())
                .toList();

        return result(
                startedAt,
                uniqueStocks.size(),
                eligibleTickers.size(),
                analyzedCount,
                candidates.size(),
                momentumQualifiedCount,
                momentumWatchCount,
                breakoutEligibleCount,
                breakoutReadyCount,
                pullbackReadyCount,
                returned,
                failures);
    }

    private List<StockSummary> deduplicate(List<StockSummary> stocks) {
        Map<String, StockSummary> unique = new LinkedHashMap<>();
        for (StockSummary stock : stocks) {
            if (stock.ticker() != null) {
                unique.putIfAbsent(stock.ticker().trim().toUpperCase(Locale.ROOT), stock);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean isEligibleEquity(StockSummary stock) {
        if (stock.ticker() == null || !B3_EQUITY_TICKER.matcher(stock.ticker().trim().toUpperCase(Locale.ROOT)).matches()) {
            return false;
        }
        if (Boolean.FALSE.equals(stock.active())) {
            return false;
        }
        if (stock.type() == null || stock.type().isBlank()) {
            return true;
        }
        String normalizedType = stock.type().trim().toUpperCase(Locale.ROOT);
        if (UNSUPPORTED_TYPES.stream().anyMatch(normalizedType::contains)) {
            return false;
        }
        return SUPPORTED_TYPES.stream().anyMatch(normalizedType::contains);
    }

    private boolean entryStatusAccepted(ScannerResult result, MarketScanCriteria criteria) {
        return criteria.entryStatuses().isEmpty()
                || result.entryAnalysis() != null && criteria.entryStatuses().contains(result.entryAnalysis().status());
    }

    private boolean liquidityAccepted(ScannerResult result, MarketScanCriteria criteria) {
        return result.technicalAnalysis() != null
                && result.technicalAnalysis().averageFinancialVolume20().compareTo(criteria.minimumAverageFinancialVolume()) >= 0;
    }

    private boolean rankingModeAccepted(ScannerResult result, MarketScanCriteria criteria) {
        return switch (criteria.rankingMode()) {
            case MOMENTUM -> result.status() == SignalStatus.QUALIFIED
                    && result.score() >= criteria.minimumScore();
            case ENTRY -> isEntryOpportunity(result, criteria);
            case BREAKOUT -> isBreakoutOpportunity(result, criteria);
            case PULLBACK -> isPullbackOpportunity(result, criteria);
        };
    }

    private boolean isEntryOpportunity(ScannerResult result, MarketScanCriteria criteria) {
        EntryStatus status = entryStatus(result);
        return switch (status) {
            case BREAKOUT_READY, PULLBACK_READY, ENTRY_READY, PULLBACK_CONFIRMED, BREAKOUT_CONFIRMED ->
                    entryScore(result) >= criteria.minimumEntryScore();
            case PULLBACK_IN_PROGRESS ->
                    criteria.entryStatuses().contains(EntryStatus.PULLBACK_IN_PROGRESS)
                            && entryScore(result) >= criteria.minimumEntryScore();
            default -> false;
        };
    }

    private boolean isBreakoutOpportunity(ScannerResult result, MarketScanCriteria criteria) {
        EntryStatus status = entryStatus(result);
        return (status == EntryStatus.BREAKOUT_READY || status == EntryStatus.WAIT_BREAKOUT)
                && entryScore(result) >= criteria.minimumEntryScore();
    }

    private boolean isPullbackOpportunity(ScannerResult result, MarketScanCriteria criteria) {
        EntryStatus status = entryStatus(result);
        return switch (status) {
            case PULLBACK_READY, ENTRY_READY, PULLBACK_CONFIRMED, PULLBACK_IN_PROGRESS ->
                    entryScore(result) >= criteria.minimumEntryScore();
            default -> false;
        };
    }

    private Comparator<ScannerResult> resultOrdering(RankingMode rankingMode) {
        if (rankingMode == RankingMode.BREAKOUT) {
            return Comparator
                    .comparing(this::entryScore, Comparator.reverseOrder())
                    .thenComparing(result -> result.technicalAnalysis().relativeVolume20(), Comparator.reverseOrder())
                    .thenComparing(result -> result.technicalAnalysis().adx14(), Comparator.reverseOrder())
                    .thenComparing(ScannerResult::ticker);
        }
        if (rankingMode == RankingMode.ENTRY) {
            return Comparator
                    .comparing(this::entryScore, Comparator.reverseOrder())
                    .thenComparing(this::confirmedSetupRank)
                    .thenComparing(ScannerResult::score, Comparator.reverseOrder())
                    .thenComparing(result -> result.technicalAnalysis().relativeVolume20(), Comparator.reverseOrder())
                    .thenComparing(ScannerResult::ticker);
        }
        return Comparator
                .comparing(ScannerResult::score, Comparator.reverseOrder())
                .thenComparing(result -> result.technicalAnalysis().relativeVolume20(), Comparator.reverseOrder())
                .thenComparing(result -> result.technicalAnalysis().adx14(), Comparator.reverseOrder())
                .thenComparing(ScannerResult::ticker);
    }

    private Integer entryScore(ScannerResult result) {
        return result.entryAnalysis() == null ? 0 : result.entryAnalysis().score();
    }

    private int confirmedSetupRank(ScannerResult result) {
        if (result.entryAnalysis() == null) {
            return 2;
        }
        EntryStatus status = result.entryAnalysis().status();
        return status == EntryStatus.BREAKOUT_READY
                || status == EntryStatus.PULLBACK_READY
                || status == EntryStatus.ENTRY_READY
                || status == EntryStatus.PULLBACK_CONFIRMED
                || status == EntryStatus.BREAKOUT_CONFIRMED ? 0 : 1;
    }

    private EntryStatus entryStatus(ScannerResult result) {
        return result.entryAnalysis() == null ? EntryStatus.NO_ENTRY_SETUP : result.entryAnalysis().status();
    }

    private boolean breakoutEligible(ScannerResult result) {
        return result.entryAnalysis() != null && result.entryAnalysis().checks().breakoutEligible();
    }

    private boolean isAnalysisFailure(ScannerResult result) {
        return result.status() == SignalStatus.REJECTED
                && result.technicalAnalysis() == null
                && result.reasons().stream().anyMatch(reason -> reason.startsWith("Insufficient candle history"));
    }

    private boolean isProviderWideFailure(ProviderErrorCode errorCode) {
        return errorCode == ProviderErrorCode.AUTHENTICATION_REQUIRED
                || errorCode == ProviderErrorCode.RATE_LIMIT_EXCEEDED
                || errorCode == ProviderErrorCode.PROVIDER_UNAVAILABLE;
    }

    private ScanFailure failure(String ticker, ProviderErrorCode errorCode) {
        return new ScanFailure(ticker, errorCode.name(), errorCode.sanitizedMessage());
    }

    private MarketScanExecutionResult result(Instant startedAt,
                                             int discoveredCount,
                                             int eligibleCount,
                                             int analyzedCount,
                                             List<ScannerResult> results,
                                             List<ScanFailure> failures) {
        return result(startedAt, discoveredCount, eligibleCount, analyzedCount, results.size(),
                0, 0, 0, 0, 0, results, failures);
    }

    private MarketScanExecutionResult result(Instant startedAt,
                                             int discoveredCount,
                                             int eligibleCount,
                                             int analyzedCount,
                                             int qualifiedCount,
                                             int momentumQualifiedCount,
                                             int momentumWatchCount,
                                             int breakoutEligibleCount,
                                             int breakoutReadyCount,
                                             int pullbackReadyCount,
                                             List<ScannerResult> results,
                                             List<ScanFailure> failures) {
        long totalDurationMillis = Duration.between(startedAt, clock.instant()).toMillis();
        return new MarketScanExecutionResult(
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS),
                ScanStocksUseCase.STRATEGY_MOMENTUM,
                discoveredCount,
                eligibleCount,
                analyzedCount,
                qualifiedCount,
                momentumQualifiedCount,
                momentumWatchCount,
                breakoutEligibleCount,
                breakoutReadyCount,
                pullbackReadyCount,
                results.size(),
                totalDurationMillis,
                analyzedCount == 0 ? 0 : totalDurationMillis / analyzedCount,
                analyzedCount + 1,
                results,
                failures);
    }
}
