package br.com.bauzin.market.panic.panicscanner.application.win.composition;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.FlowAtTimeProvider;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.StructuralContext;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureUpdate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Composes independent M5 structure events with causal intrabar flow views. */
public final class MarketFlowContextComposer {
    private final String symbol;
    private final FlowAtTimeProvider flowProvider;

    public MarketFlowContextComposer(String symbol, FlowAtTimeProvider flowProvider) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        this.symbol = symbol.trim().toUpperCase(Locale.ROOT);
        this.flowProvider = Objects.requireNonNull(flowProvider, "flowProvider must not be null");
    }

    public List<StructuralContext> compose(MarketStructureUpdate update) {
        Objects.requireNonNull(update, "update must not be null");
        return update.events().stream().map(this::compose).toList();
    }

    private StructuralContext compose(MarketStructureEvent event) {
        var flow = flowProvider.snapshotAt(symbol, event.timeMsc());
        return new StructuralContext(event, flow, flow.maxTradeTimeMscUsed());
    }
}
