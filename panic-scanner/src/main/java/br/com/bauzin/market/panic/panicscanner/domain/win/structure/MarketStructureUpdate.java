package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

import java.util.List;

public record MarketStructureUpdate(
        MovingAverageSnapshot sma9,
        MovingAverageSnapshot sma21,
        List<MarketStructureEvent> events,
        List<MovingAverageInteraction> completedInteractions) {
    public MarketStructureUpdate {
        events = List.copyOf(events);
        completedInteractions = List.copyOf(completedInteractions);
    }
}
