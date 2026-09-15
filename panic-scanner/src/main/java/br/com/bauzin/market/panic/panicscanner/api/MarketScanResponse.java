package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanFailure;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;

import java.time.OffsetDateTime;
import java.util.List;

/** API response for the automatic B3 market scan. */
public record MarketScanResponse(
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
}
