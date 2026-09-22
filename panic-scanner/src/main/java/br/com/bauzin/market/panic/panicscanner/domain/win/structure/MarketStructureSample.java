package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

import java.util.Locale;
import java.util.Objects;

public record MarketStructureSample(String symbol, long timeMsc, double price, double sma9, double sma21) {
    public MarketStructureSample {
        Objects.requireNonNull(symbol, "symbol must not be null");
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol must not be blank");
        if (timeMsc < 0) throw new IllegalArgumentException("timeMsc must not be negative");
        requirePositiveFinite(price, "price");
        requirePositiveFinite(sma9, "sma9");
        requirePositiveFinite(sma21, "sma21");
    }

    public double average(PriceReference reference) {
        return reference == PriceReference.SMA9 ? sma9 : sma21;
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
