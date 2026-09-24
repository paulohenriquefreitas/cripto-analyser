package br.com.bauzin.market.panic.panicscanner.domain.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureEvent;

import java.util.Objects;

/** M5 structural event with causal intrabar flow evidence. */
public record StructuralContext(
        MarketStructureEvent structuralEvent,
        FlowSnapshotSet flow,
        long maxFlowTradeTimeMscUsed) {

    public StructuralContext {
        Objects.requireNonNull(structuralEvent, "structuralEvent must not be null");
        Objects.requireNonNull(flow, "flow must not be null");
        if (maxFlowTradeTimeMscUsed > structuralEvent.timeMsc()) {
            throw new IllegalArgumentException("flow evidence cannot be later than structural event");
        }
    }
}
