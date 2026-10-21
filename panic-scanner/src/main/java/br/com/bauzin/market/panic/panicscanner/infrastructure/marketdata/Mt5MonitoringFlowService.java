package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.MonitoringFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.springframework.stereotype.Component;

/** Shared T&T metrics, independent of scanner scoring. */
@Component
public final class Mt5MonitoringFlowService {
    private final MonitoringFlowEngine engine = new MonitoringFlowEngine("WINV26");
    private long sequence;
    private long eventTime = -1;
    private long clockNanos;
    private boolean connected;

    public synchronized void onTrade(MarketTrade trade) {
        if (!connected) {
            engine.connected(true, trade.timeMsc());
            connected = true;
        }
        engine.onTrade(trade, ++sequence);
        advance(trade.timeMsc());
    }
    public synchronized void onLast(Mt5Tick tick) {
        engine.onLast(tick.last(), tick.timeMsc());
        advance(tick.timeMsc());
    }
    public synchronized void invalidVolume(long timeMsc) {
        connectionStatus(true, timeMsc);
        engine.invalidVolume(timeMsc);
        advance(timeMsc);
    }
    public synchronized void disconnected() {
        connected = false;
        engine.connected(false, now());
    }
    public synchronized void connectionStatus(boolean available, long timeMsc) {
        engine.connected(available, timeMsc);
        connected = available;
        advance(timeMsc);
    }
    public synchronized MonitoringFlowEngine.Snapshot snapshot() { return engine.snapshot(now()); }
    private void advance(long timestamp) {
        eventTime = Math.max(timestamp, now());
        clockNanos = System.nanoTime();
    }
    private long now() {
        // Live windows must not replay an old market timestamp as if the market were open.
        // Epoch milliseconds are UTC; the monotonic anchor protects against clock rollback.
        long utcNow = System.currentTimeMillis();
        return eventTime < 0 ? utcNow : Math.max(utcNow,
                eventTime + Math.max(0, (System.nanoTime() - clockNanos) / 1_000_000));
    }
}
