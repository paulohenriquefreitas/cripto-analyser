package br.com.bauzin.market.panic.panicscanner.domain.win.intrabar;

import java.util.Locale;
import java.util.Objects;

/** One LAST price observation, not a trade or a volume increment. */
public record CanonicalPriceEvent(String symbol, long timeMsc, double price) {
    public CanonicalPriceEvent {
        symbol = normalizeSymbol(symbol);
        M5Bucket.start(timeMsc);
        requirePrice(price);
    }

    public static String normalizeSymbol(String symbol) {
        String normalized = Objects.requireNonNull(symbol, "symbol").trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Blank symbol");
        return normalized;
    }

    public static void requirePrice(double price) {
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Invalid price");
    }
}
