package br.com.bauzin.market.panic.panicscanner.domain.entry;

import java.util.List;
import java.util.Map;

/** An independent research alert, never an order or a replacement for QUALIFIED. */
public record ExperimentalEntryAnalysis(String signal, List<String> patterns,
                                        Map<String, Boolean> checks, List<String> reasons) {
    public ExperimentalEntryAnalysis {
        patterns = List.copyOf(patterns);
        checks = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(checks));
        reasons = List.copyOf(reasons);
    }
}
