package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshotSet;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/** Builds causal flow windows ending at an explicit event-time timestamp. */
public final class FlowAtTimeProvider {
    private static final List<Duration> WINDOWS = List.of(
            TradeFlowEngine.ONE_SECOND, TradeFlowEngine.THREE_SECONDS,
            TradeFlowEngine.FIVE_SECONDS, TradeFlowEngine.TEN_SECONDS);

    private final RecentTradeBuffer buffer;

    public FlowAtTimeProvider(RecentTradeBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer must not be null");
    }

    public FlowSnapshotSet snapshotAt(String symbol, long timeMsc) {
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (timeMsc < 0) {
            throw new IllegalArgumentException("timeMsc must not be negative");
        }
        List<MarketTrade> trades = buffer.tradesAt(symbol, timeMsc);
        long maxTradeTimeMscUsed = trades.stream()
                .filter(trade -> trade.timeMsc() > timeMsc - TradeFlowEngine.TEN_SECONDS.toMillis())
                .filter(trade -> trade.timeMsc() <= timeMsc)
                .mapToLong(MarketTrade::timeMsc)
                .max()
                .orElse(-1);
        return new FlowSnapshotSet(
                snapshot(trades, TradeFlowEngine.ONE_SECOND, timeMsc),
                snapshot(trades, TradeFlowEngine.THREE_SECONDS, timeMsc),
                snapshot(trades, TradeFlowEngine.FIVE_SECONDS, timeMsc),
                snapshot(trades, TradeFlowEngine.TEN_SECONDS, timeMsc),
                maxTradeTimeMscUsed);
    }

    private FlowSnapshot snapshot(List<MarketTrade> allTrades, Duration window, long toTimeMsc) {
        long fromTimeMsc = toTimeMsc - window.toMillis();
        List<MarketTrade> selected = allTrades.stream()
                .filter(trade -> trade.timeMsc() > fromTimeMsc && trade.timeMsc() <= toTimeMsc)
                .toList();
        if (selected.isEmpty()) {
            return new FlowSnapshot(window, fromTimeMsc, toTimeMsc, 0,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, empty(), empty(), empty(), empty(), empty(), empty(), empty());
        }

        double buy = 0;
        double sell = 0;
        double ambiguous = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (MarketTrade trade : selected) {
            switch (trade.side()) {
                case BUY -> buy += trade.volume();
                case SELL -> sell += trade.volume();
                case AMBIGUOUS -> ambiguous += trade.volume();
            }
            min = Math.min(min, trade.price());
            max = Math.max(max, trade.price());
        }
        double total = buy + sell + ambiguous;
        double known = buy + sell;
        double delta = buy - sell;
        double seconds = window.toNanos() / 1_000_000_000.0;
        double firstPrice = selected.getFirst().price();
        double lastPrice = selected.getLast().price();
        return new FlowSnapshot(window, fromTimeMsc, toTimeMsc, selected.size(),
                total, buy, sell, ambiguous, known, delta,
                known == 0 ? 0 : buy / known, known == 0 ? 0 : sell / known,
                selected.size() / seconds, total / seconds,
                OptionalDouble.of(firstPrice), OptionalDouble.of(lastPrice),
                OptionalDouble.of(min), OptionalDouble.of(max),
                OptionalDouble.of(lastPrice - firstPrice), OptionalDouble.of(max - min),
                OptionalDouble.of((lastPrice - firstPrice) / seconds));
    }

    private OptionalDouble empty() {
        return OptionalDouble.empty();
    }
}
