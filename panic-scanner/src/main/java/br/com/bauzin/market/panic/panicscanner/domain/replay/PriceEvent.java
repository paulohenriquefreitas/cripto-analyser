package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.util.Locale;
import java.util.Objects;

public record PriceEvent(String symbol, long timeMsc, double price, long sequence) {
    public PriceEvent {
        Objects.requireNonNull(symbol, "symbol must not be null");
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
        if (timeMsc < 0) throw new IllegalArgumentException("timeMsc must not be negative");
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("price must be finite and positive");
        }
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
    }
}
