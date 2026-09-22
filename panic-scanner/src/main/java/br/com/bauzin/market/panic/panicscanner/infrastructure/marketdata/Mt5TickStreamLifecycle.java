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
    private volatile boolean running;
    private boolean started;
    private Mt5TickStreamClient client;
    private Thread worker;

    @Autowired
    public Mt5TickStreamLifecycle(Mt5TickWebSocketHandler handler) {
        this(handler, Mt5TickStreamClient::new);
    }

    Mt5TickStreamLifecycle(Mt5TickWebSocketHandler handler, Supplier<Mt5TickStreamClient> factory) {
        this.handler = handler;
        this.factory = factory;
    }

    @Override
    public synchronized void start() {
        if (started) return;
        started = true;
        client = factory.get();
        running = true;
        worker = Thread.ofVirtual().name("mt5-shared-stream").start(() -> {
            try {
                LOG.info("Iniciando stream MT5 compartilhado para WINV26");
                client.start(handler::broadcast);
            } catch (Exception ex) {
                if (running) LOG.error("Feed MT5 indisponivel; a aplicacao continua ativa", ex);
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                running = false;
                handler.feedUnavailable();
            }
        });
    }

    @Override
    public synchronized void stop() {
        running = false;
        try {
            if (client != null) client.stop();
            if (worker != null && worker != Thread.currentThread()) worker.join(Duration.ofSeconds(6));
        } catch (IOException ex) {
            LOG.error("Falha ao encerrar processo MT5", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("Espera de encerramento MT5 interrompida", ex);
        } finally {
            handler.feedUnavailable();
        }
    }

    @Override public boolean isRunning() { return running; }
}
