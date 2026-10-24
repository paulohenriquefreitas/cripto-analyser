package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mt5.tick-stream.enabled", havingValue = "true", matchIfMissing = true)
public class Mt5TickStreamLifecycle implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5TickStreamLifecycle.class);
    private final Mt5TickWebSocketHandler handler;
    private final Supplier<Mt5TickStreamClient> factory;
    private final Object attemptLock = new Object();
    private volatile boolean running;
    private boolean started;
    private Mt5TickStreamClient client;
    private Thread worker;

    @Autowired
    public Mt5TickStreamLifecycle(Mt5TickWebSocketHandler handler, Mt5Symbol symbol) {
        this(handler, () -> new Mt5TickStreamClient(symbol));
    }

    Mt5TickStreamLifecycle(Mt5TickWebSocketHandler handler, Supplier<Mt5TickStreamClient> factory) {
        this.handler = handler;
        this.factory = factory;
    }

    @Override
    public synchronized void start() {
        if (started) return;
        started = true;
        running = true;
        worker = Thread.ofVirtual().name("mt5-shared-stream").start(() -> {
            try {
                while (running) {
                    try {
                        Mt5TickStreamClient current;
                        synchronized (attemptLock) {
                            if (!running) break;
                            current = factory.get();
                            client = current;
                        }
                        try (current) {
                            LOG.info("Iniciando stream MT5 compartilhado com contrato configurado");
                            current.start(tick -> {
                                synchronized (attemptLock) {
                                    if (running) handler.broadcast(tick);
                                }
                            });
                        }
                    } catch (Exception ex) {
                        if (running) LOG.warn("Feed MT5 indisponivel; tentando reconectar", ex);
                    } finally {
                        synchronized (attemptLock) { client = null; }
                        handler.feedUnavailable();
                    }
                    if (running) {
                        try { Thread.sleep(1000); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                    }
                }
            } finally {
                running = false;
                handler.feedUnavailable();
            }
        });
    }

    @Override
    public synchronized void stop() {
        Mt5TickStreamClient current;
        synchronized (attemptLock) {
            running = false;
            current = client;
        }
        try {
            if (current != null) current.stop();
        } catch (IOException ex) {
            LOG.error("Falha ao encerrar processo MT5", ex);
        } finally {
            if (worker != null && worker != Thread.currentThread()) {
                worker.interrupt();
                try { worker.join(Duration.ofSeconds(6)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            handler.feedUnavailable();
        }
    }

    @Override public boolean isRunning() { return running; }
}
