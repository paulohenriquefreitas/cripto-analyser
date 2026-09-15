package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerResult;

import java.time.OffsetDateTime;
import java.util.List;

/** Result of one controlled multi-stock scan execution. */
public record ScanExecutionResult(
        OffsetDateTime scannedAt,
        String strategy,
        int requestedCount,
        int successfulCount,
        int failedCount,
        List<ScannerResult> results,
        List<ScanFailure> failures) {

    public ScanExecutionResult {
        results = List.copyOf(results);
        failures = List.copyOf(failures);
    }
}
