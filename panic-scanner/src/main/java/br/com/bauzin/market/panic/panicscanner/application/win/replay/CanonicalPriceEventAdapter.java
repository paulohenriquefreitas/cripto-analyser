package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.PriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;

import java.util.Objects;

/** Converts the canonical LAST stream without filtering or deduplicating it. */
public final class CanonicalPriceEventAdapter {
    private long nextSequence;

    public PriceEvent adapt(CanonicalPriceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new PriceEvent(event.symbol(), event.timeMsc(), event.price(), nextSequence++);
    }
}
