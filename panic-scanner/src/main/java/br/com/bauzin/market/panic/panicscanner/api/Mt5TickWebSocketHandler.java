package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick;
import com.fasterxml.jackson.core.JsonProcessingException;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class Mt5TickWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(Mt5TickWebSocketHandler.class);
    private final ObjectMapper mapper;
    private final Map<String, Connection> sessions = new ConcurrentHashMap<>();
    private TextMessage latest;
    private Mt5Tick latestTick;
    private final Ta4jMt5Sma9Adapter sma9;
    private boolean available;
    private boolean closed;

    public Mt5TickWebSocketHandler(ObjectMapper mapper, Ta4jMt5Sma9Adapter sma9) {
        this.mapper = mapper;
        this.sma9 = sma9;
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) {
        Connection connection = new Connection(session);
        if (closed) { connection.close(); return; }
        sessions.put(session.getId(), connection);
        connection.offer(status());
        if (available && latest != null) connection.offer(latest);
        connection.worker = Thread.ofVirtual().name("mt5-ws-" + session.getId()).start(connection::sendLoop);
    }

    public synchronized void broadcast(Mt5Tick tick) {
        if (closed) return;
        latestTick = tick;
        var current = sma9.onTick(tick);
        latest = serialize(new TickMessage("tick", "WINV26", tick.time(), tick.timeMsc(),
                tick.last(), tick.bid(), tick.ask(), tick.volume(),
                current == null ? null : current.value(), current == null ? null : current.time(),
                current == null ? null : current.sma21(),
                current == null || current.sma21() == null ? null : current.time()));
        available = true;
        // Queue offers only: no socket IO on the Python stdout reader thread.
        sessions.values().forEach(connection -> connection.offer(latest));
    }

    /** Re-emit the latest tick after REST seeds the new official M5 window. */
    public synchronized void refreshSma9() {
        if (available && latestTick != null) broadcast(latestTick);
    }

    public synchronized void feedUnavailable() {
        available = false;
        latest = null;
        latestTick = null;
        TextMessage message = status();
        sessions.values().forEach(connection -> connection.offer(message));
    }

    private TextMessage status() {
        return serialize(Map.of("type", "status", "available", available));
    }

    private TextMessage serialize(Object value) {
        try { return new TextMessage(mapper.writeValueAsString(value)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Falha ao serializar tick MT5", ex); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { remove(session); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        LOG.warn("Erro WebSocket MT5 na sessao {}", session.getId(), error);
        remove(session);
    }

    private void remove(WebSocketSession session) {
        Connection connection = sessions.remove(session.getId());
        if (connection != null) connection.close();
    }

    @PreDestroy
    public synchronized void shutdown() {
        closed = true;
        sessions.values().forEach(Connection::close);
        sessions.clear();
    }

    public record TickMessage(String type, String symbol, long time, long timeMsc,
                              double last, double bid, double ask, double volume, Double sma9, Long sma9Time,
                              Double sma21, Long sma21Time) {}

    private final class Connection {
        private final WebSocketSession session;
        private final ArrayBlockingQueue<TextMessage> queue = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile Thread worker;

        private Connection(WebSocketSession session) { this.session = session; }

        private void offer(TextMessage message) {
            if (ended.get()) return;
            if (!queue.offer(message)) {
                LOG.warn("Cliente WebSocket MT5 lento: encerrando sessao {}", session.getId());
                sessions.remove(session.getId(), this);
                close();
            }
        }

        private void sendLoop() {
            try {
                while (!ended.get() && session.isOpen()) session.sendMessage(queue.take());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException ex) {
                LOG.warn("Falha ao enviar tick para sessao {}", session.getId(), ex);
            } finally {
                sessions.remove(session.getId(), this);
                close();
            }
        }

        private void close() {
            if (!ended.compareAndSet(false, true)) return;
            queue.clear();
            if (worker != null) worker.interrupt();
            // Closing a slow socket can block too: keep it away from the producer.
            Thread.ofVirtual().name("mt5-ws-close").start(() -> {
                try { session.close(CloseStatus.GOING_AWAY); }
                catch (IOException ex) { LOG.debug("Erro ao fechar sessao MT5 {}", session.getId(), ex); }
            });
        }
    }
}
