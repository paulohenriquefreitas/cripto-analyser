package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplayFlowSnapshots;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

public interface TradeReplayObserver {
    TradeReplayObserver NONE = new TradeReplayObserver() {};

    default void onTrade(MarketTrade trade) {}

    /** Opt-in avoids four snapshots per event when nobody observes them. */
    default boolean observeFlowSnapshots() { return false; }

    default void onFlowSnapshots(MarketTrade trade, ReplayFlowSnapshots snapshots) {}
}
