package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import java.util.Locale;
import java.util.Objects;

public record MarketTrade(
        String symbol,
        long timeMsc,
        double price,
        double volume,
        AggressorSide side) {

    public MarketTrade {
        Objects.requireNonNull(symbol, "symbol must not be null");
        Objects.requireNonNull(side, "side must not be null");
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (timeMsc < 0) {
            throw new IllegalArgumentException("timeMsc must not be negative");
        }
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("price must be finite and positive");
        }
        if (!Double.isFinite(volume) || volume <= 0) {
            throw new IllegalArgumentException("volume must be finite and positive");
        }
    }
}
