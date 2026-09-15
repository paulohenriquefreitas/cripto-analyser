package br.com.bauzin.market.panic.panicscanner.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** API request for controlled user-provided ticker scans. */
public record ScanStocksRequest(
        @NotNull(message = "tickers is required")
        @NotEmpty(message = "tickers cannot be empty")
        List<String> tickers) {
}
