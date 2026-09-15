package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.usecase.AnalyzeStockUseCase;
import br.com.bauzin.market.panic.panicscanner.application.usecase.MarketScanCriteria;
import br.com.bauzin.market.panic.panicscanner.application.usecase.MarketScanExecutionResult;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanB3MarketUseCase;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanExecutionResult;
import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanStocksUseCase;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/panic-scanner")
public class PanicScannerController {

    private static final java.util.regex.Pattern B3_TICKER = java.util.regex.Pattern.compile("^[A-Za-z]{4}[0-9]{1,2}$");

    private final AnalyzeStockUseCase analyzeStockUseCase;
    private final ScanStocksUseCase scanStocksUseCase;
    private final ScanB3MarketUseCase scanB3MarketUseCase;
    private final PanicScannerProperties properties;

    public PanicScannerController(AnalyzeStockUseCase analyzeStockUseCase,
                                  ScanStocksUseCase scanStocksUseCase,
                                  ScanB3MarketUseCase scanB3MarketUseCase,
                                  PanicScannerProperties properties) {
        this.analyzeStockUseCase = analyzeStockUseCase;
        this.scanStocksUseCase = scanStocksUseCase;
        this.scanB3MarketUseCase = scanB3MarketUseCase;
        this.properties = properties;
    }

    @PostMapping("/analyze/{ticker}")
    public ScannerResult analyze(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z]{4}[0-9]{1,2}$", message = "ticker must be a valid B3 ticker, for example PETR4")
            String ticker) {
        if (!B3_TICKER.matcher(ticker).matches()) {
            throw new IllegalArgumentException("ticker must be a valid B3 ticker, for example PETR4");
        }
        return analyzeStockUseCase.execute(ticker);
    }

    @PostMapping("/scan")
    public ScanStocksResponse scan(@Valid @RequestBody ScanStocksRequest request) {
        validateScanRequest(request);
        ScanExecutionResult result = scanStocksUseCase.execute(request.tickers());
        return new ScanStocksResponse(
                result.scannedAt(),
                result.strategy(),
                result.requestedCount(),
                result.successfulCount(),
                result.failedCount(),
                result.results(),
                result.failures());
    }

    private void validateScanRequest(ScanStocksRequest request) {
        if (request.tickers().size() > properties.maxTickersPerScan()) {
            throw new IllegalArgumentException("ticker list cannot contain more than "
                    + properties.maxTickersPerScan() + " items");
        }
        for (String ticker : request.tickers()) {
            if (ticker == null || !B3_TICKER.matcher(ticker).matches()) {
                throw new IllegalArgumentException("ticker must be a valid B3 ticker, for example PETR4");
            }
        }
    }

    @PostMapping("/market-scan")
    public MarketScanResponse marketScan(@RequestBody(required = false) MarketScanRequest request) {
        MarketScanCriteria criteria = criteriaFrom(request);
        MarketScanExecutionResult result = scanB3MarketUseCase.execute(criteria);
        return new MarketScanResponse(
                result.scannedAt(),
                result.strategy(),
                result.discoveredCount(),
                result.eligibleCount(),
                result.analyzedCount(),
                result.qualifiedCount(),
                result.momentumQualifiedCount(),
                result.momentumWatchCount(),
                result.breakoutEligibleCount(),
                result.breakoutReadyCount(),
                result.pullbackReadyCount(),
                result.returnedCount(),
                result.totalExecutionDurationMillis(),
                result.averageAnalysisDurationMillis(),
                result.providerRequestCount(),
                result.results(),
                result.failures());
    }

    private MarketScanCriteria criteriaFrom(MarketScanRequest request) {
        BigDecimal minimumAverageFinancialVolume = request == null || request.minimumAverageFinancialVolume() == null
                ? properties.minAverageFinancialVolume20()
                : request.minimumAverageFinancialVolume();
        int minimumScore = request == null || request.minimumScore() == null
                ? request == null || request.minimumMomentumScore() == null
                ? properties.minimumQualifiedScore()
                : request.minimumMomentumScore()
                : request.minimumScore();
        int minimumEntryScore = request == null || request.minimumEntryScore() == null ? 0 : request.minimumEntryScore();
        RankingMode rankingMode = request == null || request.rankingMode() == null ? RankingMode.MOMENTUM : request.rankingMode();
        int limit = request == null || request.limit() == null
                ? properties.maxMarketScanResults()
                : request.limit();
        if (minimumAverageFinancialVolume.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("minimumAverageFinancialVolume must be greater than or equal to zero");
        }
        if (minimumScore < 0 || minimumScore > 100) {
            throw new IllegalArgumentException("minimumMomentumScore must be between 0 and 100");
        }
        if (minimumEntryScore < 0 || minimumEntryScore > 100) {
            throw new IllegalArgumentException("minimumEntryScore must be between 0 and 100");
        }
        if (limit <= 0 || limit > properties.maxMarketScanResults()) {
            throw new IllegalArgumentException("limit must be between 1 and " + properties.maxMarketScanResults());
        }
        return new MarketScanCriteria(
                minimumAverageFinancialVolume,
                minimumScore,
                minimumEntryScore,
                request == null ? java.util.List.of() : request.entryStatuses(),
                rankingMode,
                limit);
    }
}
