package br.com.bauzin.market.panic.panicscanner.domain.replay;

public enum TerminationReason {
    OPEN,
    TARGET,
    STOP,
    SAME_EVENT_AMBIGUOUS,
    HORIZON_REACHED,
    REPLAY_ENDED
}
