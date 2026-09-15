package br.com.bauzin.market.panic.panicscanner.domain.win;

public enum WinExecutionStatus {
    NO_TRADE,
    WATCH_BUY,
    WATCH_SELL,
    BUY_SETUP,
    SELL_SETUP,
    BUY_TRIGGERED,
    SELL_TRIGGERED,
    OVEREXTENDED_UP,
    OVEREXTENDED_DOWN,
    REVERSAL_CANDIDATE_UP,
    REVERSAL_CANDIDATE_DOWN,
    REVERSAL_CONFIRMED_UP,
    REVERSAL_CONFIRMED_DOWN
}
