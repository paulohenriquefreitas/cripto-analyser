package br.com.bauzin.market.panic.panicscanner.application;

/** Sanitized market-data provider error categories exposed to clients. */
public enum ProviderErrorCode {
    AUTHENTICATION_REQUIRED("Market data provider authentication is required"),
    RATE_LIMIT_EXCEEDED("Market data provider rate limit exceeded"),
    TICKER_NOT_FOUND("Ticker was not found by the market data provider"),
    INSUFFICIENT_HISTORY("Insufficient candle history for technical analysis"),
    PROVIDER_UNAVAILABLE("Market data provider is unavailable"),
    INVALID_PROVIDER_RESPONSE("Market data provider returned an invalid response"),
    UNEXPECTED_ERROR("Unexpected error while analyzing ticker");

    private final String sanitizedMessage;

    ProviderErrorCode(String sanitizedMessage) {
        this.sanitizedMessage = sanitizedMessage;
    }

    public String sanitizedMessage() {
        return sanitizedMessage;
    }
}
