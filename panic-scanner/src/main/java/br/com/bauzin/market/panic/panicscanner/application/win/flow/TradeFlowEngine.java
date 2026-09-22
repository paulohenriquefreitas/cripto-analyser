package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class TradeFlowEngine {
    public static final Duration ONE_SECOND = Duration.ofSeconds(1);
    public static final Duration THREE_SECONDS = Duration.ofSeconds(3);
    public static final Duration FIVE_SECONDS = Duration.ofSeconds(5);
    public static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private static final Duration[] SUPPORTED_WINDOWS = {
            ONE_SECOND, THREE_SECONDS, FIVE_SECONDS, TEN_SECONDS
    };

    private final Map<Duration, RollingTradeWindow> windows = new LinkedHashMap<>();
    private String symbol;
    private long lastTimeMsc = -1;

    public TradeFlowEngine() {
        for (Duration duration : SUPPORTED_WINDOWS) {
            windows.put(duration, new RollingTradeWindow(duration));
        }
    }

    public synchronized void onTrade(MarketTrade trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        if (symbol != null && !symbol.equals(trade.symbol())) {
            throw new IllegalArgumentException(
                    "trade symbol " + trade.symbol() + " differs from engine symbol " + symbol + "; reset first");
        }
        if (lastTimeMsc > trade.timeMsc()) {
            throw new IllegalArgumentException(
                    "trades must be ordered by non-decreasing timeMsc: " + trade.timeMsc() + " < " + lastTimeMsc);
        }
        symbol = trade.symbol();
        lastTimeMsc = trade.timeMsc();
        windows.values().forEach(window -> window.add(trade));
    }

    public synchronized FlowSnapshot snapshot(Duration window) {
        Objects.requireNonNull(window, "window must not be null");
        RollingTradeWindow state = windows.get(window);
        if (state == null) {
            throw new IllegalArgumentException("unsupported flow window: " + window);
        }
        return state.snapshot();
    }

    public synchronized void reset() {
        windows.values().forEach(RollingTradeWindow::clear);
        symbol = null;
        lastTimeMsc = -1;
    }
}
