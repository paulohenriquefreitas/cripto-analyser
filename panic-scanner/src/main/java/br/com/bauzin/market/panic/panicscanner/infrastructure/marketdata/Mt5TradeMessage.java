package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

record Mt5TradeMessage(
        String type,
        String symbol,
        long timeMsc,
        double price,
        double volume,
        AggressorSide side) {

    MarketTrade toMarketTrade() {
        if (!"trade".equals(type)) {
            throw new IllegalArgumentException("unsupported message type: " + type);
        }
        return new MarketTrade(symbol, timeMsc, price, volume, side);
    }
}
