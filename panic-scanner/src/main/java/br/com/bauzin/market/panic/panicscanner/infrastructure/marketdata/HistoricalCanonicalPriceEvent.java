package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;

public record HistoricalCanonicalPriceEvent(
        CanonicalPriceEvent priceEvent,
        double volumeReal,
        int flags,
        long sequence) {
}
