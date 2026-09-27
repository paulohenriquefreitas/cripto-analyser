package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.M5ReplaySession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public final class ReplayWebSocketHandler extends TextWebSocketHandler {
    private final ReplaySessionRegistry registry;
    private final ObjectMapper mapper;

    public ReplayWebSocketHandler(ReplaySessionRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        M5ReplaySession session = registry.get(id(socket));
        socket.getAttributes().put("replayListener", (java.util.function.Consumer<M5ReplaySession.Snapshot>)
                snapshot -> send(socket, snapshot));
        session.addListener((java.util.function.Consumer<M5ReplaySession.Snapshot>) socket.getAttributes().get("replayListener"));
        socket.getAttributes().put("ruleOccurrenceListener",
                (java.util.function.Consumer<M5ReplaySession.RuleOccurrenceMessage>)
                        occurrence -> send(socket, occurrence));
        session.addRuleOccurrenceListener((java.util.function.Consumer<M5ReplaySession.RuleOccurrenceMessage>)
                socket.getAttributes().get("ruleOccurrenceListener"));
        socket.getAttributes().put("setupEventListener",
                (java.util.function.Consumer<M5ReplaySession.SetupEventMessage>)
                        setupEvent -> send(socket, setupEvent));
        session.addSetupEventListener((java.util.function.Consumer<M5ReplaySession.SetupEventMessage>)
                socket.getAttributes().get("setupEventListener"));
        send(socket, new M5ReplaySession.Snapshot("CLOCK", session.replayId(), session.currentTimeMsc(),
                session.status(), null, null, null, null, null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        M5ReplaySession session = registry.get(id(socket));
        String command = message.getPayload().trim().toUpperCase();
        if ("PLAY".equals(command)) session.play();
        else if ("PAUSE".equals(command)) session.pause();
        else send(socket, Map.of("type", "ERROR", "message", "Only PLAY and PAUSE are supported"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        M5ReplaySession session = registry.get(id(socket));
        Object listener = socket.getAttributes().remove("replayListener");
        if (listener instanceof java.util.function.Consumer<?> consumer) {
            session.removeListener((java.util.function.Consumer<M5ReplaySession.Snapshot>) consumer);
        }
        Object ruleOccurrenceListener = socket.getAttributes().remove("ruleOccurrenceListener");
        if (ruleOccurrenceListener instanceof java.util.function.Consumer<?> consumer) {
            session.removeRuleOccurrenceListener(
                    (java.util.function.Consumer<M5ReplaySession.RuleOccurrenceMessage>) consumer);
        }
        Object setupEventListener = socket.getAttributes().remove("setupEventListener");
        if (setupEventListener instanceof java.util.function.Consumer<?> consumer) {
            session.removeSetupEventListener(
                    (java.util.function.Consumer<M5ReplaySession.SetupEventMessage>) consumer);
        }
        registry.close(session.replayId());
    }

    private String id(WebSocketSession socket) {
        String path = socket.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void send(WebSocketSession socket, Object value) {
        try {
            if (socket.isOpen()) socket.sendMessage(new TextMessage(mapper.writeValueAsString(value)));
        } catch (Exception ignored) {
            // Transport failures are handled by the WebSocket lifecycle.
        }
    }
}
