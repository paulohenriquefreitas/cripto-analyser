package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bounded event-time history for causal flow lookups. */
public final class RecentTradeBuffer {
    static final Duration MAX_FLOW_WINDOW = TradeFlowEngine.TEN_SECONDS;

    private final long retentionMsc;
    private final Deque<MarketTrade> trades = new ArrayDeque<>();
    private String symbol;
    private long lastTimeMsc = -1;

    public RecentTradeBuffer() {
        this(MAX_FLOW_WINDOW);
    }

    public RecentTradeBuffer(Duration retention) {
        Objects.requireNonNull(retention, "retention must not be null");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (retention.compareTo(MAX_FLOW_WINDOW) < 0) {
            throw new IllegalArgumentException("retention must cover the ten-second flow window");
        }
        retentionMsc = retention.toMillis();
    }

    public synchronized void add(MarketTrade trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        if (symbol != null && !symbol.equals(trade.symbol())) {
            throw new IllegalArgumentException("trade symbol differs from buffer symbol; reset first");
        }
        if (lastTimeMsc > trade.timeMsc()) {
            throw new IllegalArgumentException(
                    "trades must be ordered by non-decreasing timeMsc: "
                            + trade.timeMsc() + " < " + lastTimeMsc);
        }
        symbol = trade.symbol();
        lastTimeMsc = trade.timeMsc();
        trades.addLast(trade);
        expireAt(lastTimeMsc);
    }

    synchronized List<MarketTrade> tradesAt(String requestedSymbol, long timeMsc) {
        Objects.requireNonNull(requestedSymbol, "symbol must not be null");
        if (timeMsc < 0) {
            throw new IllegalArgumentException("timeMsc must not be negative");
        }
        if (symbol != null && !symbol.equals(requestedSymbol.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("requested symbol differs from buffer symbol");
        }
        List<MarketTrade> result = new ArrayList<>();
        for (MarketTrade trade : trades) {
            if (trade.timeMsc() <= timeMsc) {
                result.add(trade);
            }
        }
        return List.copyOf(result);
    }

    public synchronized int size() {
        return trades.size();
    }

    public synchronized void reset() {
        trades.clear();
        symbol = null;
        lastTimeMsc = -1;
    }

    private void expireAt(long currentTimeMsc) {
        long oldestRetained = currentTimeMsc - retentionMsc;
        while (!trades.isEmpty() && trades.peekFirst().timeMsc() <= oldestRetained) {
            trades.removeFirst();
        }
    }
}
