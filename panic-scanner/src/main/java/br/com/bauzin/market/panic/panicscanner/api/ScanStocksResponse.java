package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.usecase.ScanFailure;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;

import java.time.OffsetDateTime;
import java.util.List;

/** API response for controlled user-provided ticker scans. */
public record ScanStocksResponse(
        OffsetDateTime scannedAt,
        String strategy,
        int requestedCount,
        int successfulCount,
        int failedCount,
        List<ScannerResult> results,
        List<ScanFailure> failures) {
}
