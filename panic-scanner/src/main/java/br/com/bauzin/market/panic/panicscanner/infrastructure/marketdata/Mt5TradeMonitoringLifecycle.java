package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One persistent Python stream for all charts; reconnect resumes the ordinal cursor. */
@Component
@ConditionalOnProperty(name = "mt5.trade-monitoring.enabled", havingValue = "true", matchIfMissing = true)
public final class Mt5TradeMonitoringLifecycle implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5TradeMonitoringLifecycle.class);
    private final Mt5MonitoringFlowService flow;
    private final Mt5TickWebSocketHandler handler;
    private final Mt5VolumeProfileService profile;
    private final Mt5Symbol symbol;
    private volatile boolean running;
    private volatile Mt5TradeStreamClient client;
    private Thread reader;
    private Thread publisher;
    public Mt5TradeMonitoringLifecycle(Mt5MonitoringFlowService flow, Mt5TickWebSocketHandler handler,
                                       Mt5VolumeProfileService profile, Mt5Symbol symbol) {
        this.flow = flow;
        this.handler = handler;
        this.profile = profile;
        this.symbol = symbol;
    }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        reader = Thread.ofVirtual().name("mt5-monitor-trades").start(() -> {
            Mt5TradeStreamClient.Checkpoint checkpoint = null;
            while (running) {
                var current = new Mt5TradeStreamClient(symbol,checkpoint);
                client = current;
                try (current) {
                    current.startWithCursor((trade, identity) -> {
                        flow.onTrade(trade);
                        profile.onTrade(trade, identity);
                    }, time -> { flow.invalidVolume(time); profile.invalidVolume(time); },
                    (available, time) -> { flow.connectionStatus(available,time); profile.connectionStatus(available,time); });
                } catch (Exception ex) {
                    if (running) LOG.warn("Times & Trades indisponivel; retomando pelo cursor", ex);
                } finally {
                    checkpoint = current.checkpoint();
                    flow.disconnected();
                    profile.disconnected();
                }
                if (running) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                }
            }
        });
        publisher = Thread.ofVirtual().name("mt5-monitor-flow").start(() -> {
            while (running) {
                try {
                    handler.broadcastFlow(flow.snapshot());
                    Thread.sleep(250);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                catch (RuntimeException ex) { LOG.error("Falha ao publicar metricas T&T", ex); }
            }
        });
    }
    @Override public synchronized void stop() {
        running = false;
        if (publisher != null) publisher.interrupt();
        if (reader != null) reader.interrupt();
        try { if (client != null) client.close(); }
        catch (Exception ex) { LOG.warn("Falha ao encerrar captura T&T", ex); }
        flow.disconnected();
        profile.disconnected();
    }
    @Override public boolean isRunning() { return running; }
}
