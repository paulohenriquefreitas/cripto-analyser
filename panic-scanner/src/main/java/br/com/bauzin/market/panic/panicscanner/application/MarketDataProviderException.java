package br.com.bauzin.market.panic.panicscanner.application;

/** Sanitized exception raised when a market-data provider cannot fulfill a request. */
public class MarketDataProviderException extends RuntimeException {

    private final ProviderErrorCode errorCode;

    public MarketDataProviderException(ProviderErrorCode errorCode) {
        super(errorCode.sanitizedMessage());
        this.errorCode = errorCode;
    }

    public ProviderErrorCode errorCode() {
        return errorCode;
    }
}
