package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.MonitoringFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** FLOW-only clock adapter. Source timestamps and the shared capture cursor remain untouched. */
@Component
public final class Mt5MonitoringFlowService {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5MonitoringFlowService.class);
    private final String symbol;
    private final LongSupplier utcMillis;
    private final LongSupplier nanoTime;
    private MonitoringFlowEngine engine;
    private long shiftMillis;
    private boolean timeBasisConfirmed;
    private long sequence;
    private long referenceTime = -1;
    private long clockNanos;
    private boolean connected;
    private long rejectedOld;
    private long rejectedFuture;
    private long rejectedRegression;

    public Mt5MonitoringFlowService() { this(Mt5Symbol.defaults()); }
    public Mt5MonitoringFlowService(Mt5Symbol symbol) { this(symbol, 180, false); }
    @Autowired
    public Mt5MonitoringFlowService(Mt5Symbol symbol,
            @Value("${mt5.flow.feed-time-shift-minutes:180}") long shiftMinutes,
            @Value("${mt5.flow.time-basis-confirmed:false}") boolean confirmed) {
        this(symbol, shiftMinutes, confirmed, System::currentTimeMillis, System::nanoTime);
    }
    Mt5MonitoringFlowService(Mt5Symbol symbol, long shiftMinutes, boolean confirmed,
                             LongSupplier utcMillis, LongSupplier nanoTime) {
        this.symbol = symbol.value();
        this.utcMillis = utcMillis;
        this.nanoTime = nanoTime;
        engine = new MonitoringFlowEngine(this.symbol);
        configureTimeBasis(shiftMinutes, confirmed);
        if (!confirmed) LOG.warn("FLOW time basis is unvalidated: raw feed + {} minutes = UTC; see timeBasis in FLOW snapshots", shiftMinutes);
    }

    /** Also used by any future configuration reload: never mix buffers from different clocks. */
    public synchronized void configureTimeBasis(long shiftMinutes, boolean confirmed) {
        long shift = Math.multiplyExact(shiftMinutes, 60_000);
        if (shift == shiftMillis && confirmed == timeBasisConfirmed) return;
        shiftMillis = shift;
        timeBasisConfirmed = confirmed;
        engine = new MonitoringFlowEngine(symbol);
        sequence = 0;
        referenceTime = -1;
        rejectedOld = rejectedFuture = rejectedRegression = 0;
        engine.connected(connected, now());
    }

    public synchronized void onTrade(MarketTrade trade) {
        if (!symbol.equals(trade.symbol())) throw new IllegalArgumentException("monitor symbol mismatch");
        long now = now();
        if (!admissible(trade.timeMsc(), now)) return;
        if (!connected) {
            engine.connected(true, now);
            connected = true;
        }
        if (!engine.onTrade(trade, ++sequence)) rejectedRegression++;
    }
    public synchronized void onLast(Mt5Tick tick) {
        long now = now();
        if (admissible(tick.timeMsc(), now)) engine.onLast(tick.last(), tick.timeMsc());
    }
    private boolean admissible(long timestamp, long now) {
        // Never refresh the clock from receipt of history/backlog. Windows are (now-duration, now].
        if (timestamp <= now - 30_000) { rejectedOld++; return false; }
        if (timestamp > now + 2_000) { rejectedFuture++; return false; }
        return true;
    }
    public synchronized void invalidVolume(long timeMsc) {
        long now = now();
        connectionStatus(true, timeMsc);
        if (admissible(timeMsc, now)) engine.invalidVolume(timeMsc);
    }
    public synchronized void disconnected() {
        connected = false;
        engine.connected(false, now());
    }
    public synchronized void connectionStatus(boolean available, long ignoredSourceTimestamp) {
        connected = available;
        engine.connected(available, now());
    }
    public synchronized MonitoringFlowEngine.Snapshot snapshot() {
        var value = engine.snapshot(now());
        return new MonitoringFlowEngine.Snapshot(value.type(), value.symbol(), value.timeMsc(),
                value.lastTimeMsc(), value.last(), value.volumeSource(), value.windows(),
                new MonitoringFlowEngine.TimeBasis(shiftMillis / 60_000, timeBasisConfirmed,
                        rejectedOld, rejectedFuture, rejectedRegression));
    }
    private long now() {
        // +180 converts raw feed -> UTC, hence UTC -180 is the matching raw-feed reference.
        long adjusted = Math.subtractExact(utcMillis.getAsLong(), shiftMillis);
        long nanos = nanoTime.getAsLong();
        if (referenceTime < 0) {
            referenceTime = adjusted;
            clockNanos = nanos;
        } else {
            long elapsedMillis = Math.max(0, (nanos - clockNanos) / 1_000_000);
            long monotonic = referenceTime + elapsedMillis;
            if (adjusted > monotonic) {
                referenceTime = adjusted;
                clockNanos = nanos;
            } else {
                referenceTime = monotonic;
                // Preserve fractional milliseconds even under frequent calls and UTC rollback.
                clockNanos += elapsedMillis * 1_000_000;
            }
        }
        return referenceTime;
    }
}
