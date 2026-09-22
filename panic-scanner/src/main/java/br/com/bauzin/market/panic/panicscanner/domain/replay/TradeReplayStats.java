package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.util.OptionalDouble;
import java.util.OptionalLong;

public record TradeReplayStats(
        long trades,
        long buyTrades,
        long sellTrades,
        long ambiguousTrades,
        double totalVolume,
        double buyVolume,
        double sellVolume,
        double ambiguousVolume,
        double knownDelta,
        OptionalDouble firstPrice,
        OptionalDouble lastPrice,
        OptionalDouble minPrice,
        OptionalDouble maxPrice,
        OptionalLong firstTimeMsc,
        OptionalLong lastTimeMsc) {
}
