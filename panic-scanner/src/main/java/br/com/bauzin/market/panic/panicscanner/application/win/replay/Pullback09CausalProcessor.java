package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5HistoricalPriceSource;
import java.util.ArrayList;
import java.util.List;

/** Shared causal sequence for Replay and a single historical session. No IO or visual publication. */
final class Pullback09CausalProcessor {
    record Update(IntrabarMarketState state, Double vwap, double volume,
                  List<Pullback09Setup.Event> events) {}

    private final IntrabarM5Processor processor = new IntrabarM5Processor();
    private final CausalSessionVwap vwap = new CausalSessionVwap();
    private final Pullback09Setup up = new Pullback09Setup();
    private final Pullback09DownSetup down = new Pullback09DownSetup();
    private final Pullback09ResearchEngine research;

    Pullback09CausalProcessor(Pullback09ResearchEngine research) {
        this.research = research;
    }

    void warmUp(Mt5HistoricalPriceSource.Header header) {
        processor.warmUp(header.symbol(), header.startMsc(), header.warmup().stream()
                .map(c -> new IntrabarCandleSnapshot(header.symbol(), c.time() * 1000,
                        c.open(), c.high(), c.low(), c.close())).toList());
        vwap.warmUp(header.symbol(), header.warmup());
        research.warmUp(header.symbol(), header.warmup());
    }

    Update accept(HistoricalCanonicalPriceEvent historical) {
        var event = historical.priceEvent();
        var state = processor.onEvent(event);
        if (state.completedCandle() != null) research.onCandleClosed(state.completedCandle());
        double volume = CausalSessionVwap.isEligibleVolume(historical) ? historical.volumeReal() : 0;
        Double value = vwap.update(event.timeMsc(), event.price(), volume);
        var events = new ArrayList<>(up.onEvent(state));
        events.addAll(down.onEvent(state));
        for (var setup : events) {
            if (setup.eventType() == Pullback09Setup.EventType.PULLB09_UP
                    || setup.eventType() == Pullback09Setup.EventType.PULLB09_DOWN) {
                research.onPullback09(setup, setup.eventType() == Pullback09Setup.EventType.PULLB09_UP
                        ? up.lastPullback09Context() : down.lastPullback09Context(), state, value);
            }
        }
        research.onPrice(event);
        return new Update(state, value, vwap.currentVolume(), events);
    }
}
