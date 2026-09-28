package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.Serializable;

/**
 * Causal outcome evaluation across post-availability SETUP OUTCOME (not a trade) time horizons (5m, 15m, 30m).
 * Incomplete windows (where replay terminated before elapsed horizon) are explicitly flagged with complete = false
 * and null excursion metrics.
 */
public record Pullback09SetupOutcome(
        String eventId,
        boolean complete5m,
        Double mfe5m,
        Double mae5m,
        boolean complete15m,
        Double mfe15m,
        Double mae15m,
        boolean complete30m,
        Double mfe30m,
        Double mae30m
) implements Serializable {}
