package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.util.Locale;
import java.util.Objects;

public record RuleOccurrence(
        String occurrenceId,
        String ruleId,
        String symbol,
        long timeMsc,
        double entryPrice,
        RuleDirection direction) {
    public RuleOccurrence {
        requireText(occurrenceId, "occurrenceId");
        requireText(ruleId, "ruleId");
        requireText(symbol, "symbol");
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (timeMsc < 0) throw new IllegalArgumentException("timeMsc must not be negative");
        if (!Double.isFinite(entryPrice) || entryPrice <= 0) {
            throw new IllegalArgumentException("entryPrice must be finite and positive");
        }
        Objects.requireNonNull(direction, "direction must not be null");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
