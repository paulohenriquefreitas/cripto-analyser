package br.com.bauzin.market.panic.panicscanner.application.usecase;

/** One ticker failure captured during a controlled scan. */
public record ScanFailure(
        String ticker,
        String errorCode,
        String message) {
}
