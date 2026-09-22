package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.util.ArrayList;
import java.util.List;

/** Ordered validation projection; ordinal is diagnostic metadata, not trade identity. */
public record TradeValidationEvent(
        long timeMsc,
        int ordinalWithinTimestamp,
        double price,
        double volume,
        AggressorSide side) {

    static List<TradeValidationEvent> fromTrades(List<MarketTrade> trades) {
        List<TradeValidationEvent> result = new ArrayList<>(trades.size());
        long groupTime = Long.MIN_VALUE;
        int ordinal = 0;
        for (MarketTrade trade : trades) {
            if (trade.timeMsc() != groupTime) {
                groupTime = trade.timeMsc();
                ordinal = 0;
            }
            result.add(new TradeValidationEvent(
                    trade.timeMsc(), ordinal++, trade.price(), trade.volume(), trade.side()));
        }
        return List.copyOf(result);
    }

    boolean sameTrade(TradeValidationEvent other) {
        return timeMsc == other.timeMsc
                && Double.doubleToLongBits(price) == Double.doubleToLongBits(other.price)
                && Double.doubleToLongBits(volume) == Double.doubleToLongBits(other.volume)
                && side == other.side;
    }
}
