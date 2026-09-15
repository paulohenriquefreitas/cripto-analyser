package br.com.bauzin.market.panic.panicscanner.domain.entry;

/** Classification of the current swing-trade entry setup. */
public enum EntryStatus {
    BREAKOUT_READY,
    PULLBACK_READY,
    ENTRY_READY,
    PULLBACK_CONFIRMED,
    BREAKOUT_CONFIRMED,
    PULLBACK_IN_PROGRESS,
    WATCH,
    WAIT_PULLBACK,
    WAIT_BREAKOUT,
    POTENTIAL_BREAKOUT,
    OVEREXTENDED,
    TREND_WEAKENING,
    INVALIDATED,
    NO_ENTRY_SETUP
}
