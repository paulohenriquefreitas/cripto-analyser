package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;

import java.util.List;

public record TradeSequenceStats(
        long trades, long buyTrades, long sellTrades, long ambiguousTrades,
        double totalVolume, double buyVolume, double sellVolume, double ambiguousVolume,
        double knownDelta, double firstPrice, double lastPrice, double minPrice,
        double maxPrice, double priceChange, long timestampsWithMultipleTrades,
        int maxTradesAtSameTimeMsc, long tradesSharingTimeMsc,
        long identicalConsecutiveTrades) {

    static TradeSequenceStats calculate(List<TradeValidationEvent> events) {
        if (events.isEmpty()) {
            return new TradeSequenceStats(0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0, 0, 0, 0);
        }
        long buy = 0, sell = 0, ambiguous = 0, multiTimes = 0, shared = 0, identical = 0;
        double total = 0, buyVolume = 0, sellVolume = 0, ambiguousVolume = 0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int maxAtTime = 0, groupSize = 0;
        long groupTime = Long.MIN_VALUE;
        TradeValidationEvent previous = null;
        for (TradeValidationEvent event : events) {
            if (event.timeMsc() != groupTime) {
                if (groupSize > 1) { multiTimes++; shared += groupSize; }
                maxAtTime = Math.max(maxAtTime, groupSize);
                groupTime = event.timeMsc();
                groupSize = 0;
            }
            groupSize++;
            total += event.volume();
            min = Math.min(min, event.price());
            max = Math.max(max, event.price());
            if (event.side() == AggressorSide.BUY) { buy++; buyVolume += event.volume(); }
            else if (event.side() == AggressorSide.SELL) { sell++; sellVolume += event.volume(); }
            else { ambiguous++; ambiguousVolume += event.volume(); }
            if (previous != null && event.sameTrade(previous)) identical++;
            previous = event;
        }
        if (groupSize > 1) { multiTimes++; shared += groupSize; }
        maxAtTime = Math.max(maxAtTime, groupSize);
        double first = events.getFirst().price();
        double last = events.getLast().price();
        return new TradeSequenceStats(events.size(), buy, sell, ambiguous, total,
                buyVolume, sellVolume, ambiguousVolume, buyVolume - sellVolume,
                first, last, min, max, last - first, multiTimes, maxAtTime, shared, identical);
    }
}
