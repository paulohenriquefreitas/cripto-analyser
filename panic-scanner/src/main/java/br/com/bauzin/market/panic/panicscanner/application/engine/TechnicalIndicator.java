package br.com.bauzin.market.panic.panicscanner.application.engine;

/** Indicator capabilities planned for technical-analysis engines. */
public enum TechnicalIndicator {
    SMA(true),
    RSI(true),
    EMA(true),
    ATR(true),
    MACD(false),
    ADX(true),
    VWAP(false),
    BOLLINGER(false),
    VOLUME(false),
    SUPPORT(false),
    RESISTANCE(false);

    private final boolean implemented;

    TechnicalIndicator(boolean implemented) {
        this.implemented = implemented;
    }

    public boolean implemented() {
        return implemented;
    }
}
