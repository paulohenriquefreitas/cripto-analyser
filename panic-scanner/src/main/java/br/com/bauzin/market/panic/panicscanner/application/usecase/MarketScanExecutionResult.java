package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;

import java.time.OffsetDateTime;
import java.util.List;

/** Result of one automatic B3 stock-universe scan execution. */
public record MarketScanExecutionResult(
        OffsetDateTime scannedAt,
        String strategy,
        int discoveredCount,
        int eligibleCount,
        int analyzedCount,
        int qualifiedCount,
        int momentumQualifiedCount,
        int momentumWatchCount,
        int breakoutEligibleCount,
        int breakoutReadyCount,
        int pullbackReadyCount,
        int returnedCount,
        long totalExecutionDurationMillis,
        long averageAnalysisDurationMillis,
        int providerRequestCount,
        List<ScannerResult> results,
        List<ScanFailure> failures) {

    public MarketScanExecutionResult {
        results = List.copyOf(results);
        failures = List.copyOf(failures);
    }
}
