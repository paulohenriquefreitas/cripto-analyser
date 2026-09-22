package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.util.Locale;
import java.util.Objects;

/** Inclusive event-time bounds; loadStart may precede analysisStart for warm-up. */
public record ReplaySession(String symbol, long loadStartTimeMsc,
                            long analysisStartTimeMsc, long analysisEndTimeMsc) {
    public ReplaySession {
        Objects.requireNonNull(symbol, "symbol");
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isEmpty()) throw new IllegalArgumentException("symbol must not be blank");
        if (loadStartTimeMsc < 0 || loadStartTimeMsc > analysisStartTimeMsc
                || analysisStartTimeMsc > analysisEndTimeMsc) {
            throw new IllegalArgumentException("required: 0 <= loadStart <= analysisStart <= analysisEnd");
        }
    }

    public boolean warmup(long timeMsc) {
        return timeMsc >= loadStartTimeMsc && timeMsc < analysisStartTimeMsc;
    }

    public boolean analysis(long timeMsc) {
        return timeMsc >= analysisStartTimeMsc && timeMsc <= analysisEndTimeMsc;
    }
}
