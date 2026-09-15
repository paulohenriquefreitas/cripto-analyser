package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.SignalStatus;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Orchestrates a controlled sequential scan of user-provided B3 tickers. */
@Service
public class ScanStocksUseCase {

    public static final String STRATEGY_MOMENTUM = "MOMENTUM";

    private final AnalyzeStockUseCase analyzeStockUseCase;
    private final Clock clock;

    public ScanStocksUseCase(AnalyzeStockUseCase analyzeStockUseCase, Clock clock) {
        this.analyzeStockUseCase = analyzeStockUseCase;
        this.clock = clock;
    }

    public ScanExecutionResult execute(List<String> tickers) {
        List<String> normalizedTickers = normalizeAndDeduplicate(tickers);
        List<ScannerResult> results = new ArrayList<>();
        List<ScanFailure> failures = new ArrayList<>();

        for (String ticker : normalizedTickers) {
            try {
                ScannerResult result = analyzeStockUseCase.execute(ticker);
                if (isAnalysisFailure(result)) {
                    failures.add(failure(ticker, ProviderErrorCode.INSUFFICIENT_HISTORY));
                } else {
                    results.add(result);
                }
            } catch (MarketDataProviderException exception) {
                failures.add(failure(ticker, exception.errorCode()));
            } catch (RuntimeException exception) {
                failures.add(failure(ticker, ProviderErrorCode.UNEXPECTED_ERROR));
            }
        }

        List<ScannerResult> rankedResults = results.stream()
                .sorted(Comparator
                        .comparing(ScannerResult::score, Comparator.reverseOrder())
                        .thenComparing(ScannerResult::ticker))
                .toList();

        return new ScanExecutionResult(
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS),
                STRATEGY_MOMENTUM,
                normalizedTickers.size(),
                rankedResults.size(),
                failures.size(),
                rankedResults,
                failures);
    }

    private List<String> normalizeAndDeduplicate(List<String> tickers) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String ticker : tickers) {
            normalized.add(ticker.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private boolean isAnalysisFailure(ScannerResult result) {
        return result.status() == SignalStatus.REJECTED
                && result.technicalAnalysis() == null
                && result.reasons().stream().anyMatch(reason -> reason.startsWith("Insufficient candle history"));
    }

    private ScanFailure failure(String ticker, ProviderErrorCode errorCode) {
        return new ScanFailure(ticker, errorCode.name(), errorCode.sanitizedMessage());
    }
}
