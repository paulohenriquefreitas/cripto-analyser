package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.M5ReplaySession;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class ReplaySessionRegistry {
    private final Map<String, M5ReplaySession> sessions = new ConcurrentHashMap<>();

    public M5ReplaySession create(Path historyFile, String symbol) {
        if (!Files.isRegularFile(historyFile)) throw new IllegalArgumentException("History file not found");
        M5ReplaySession session = new M5ReplaySession(historyFile, symbol);
        sessions.put(session.replayId(), session);
        return session;
    }

    public M5ReplaySession get(String replayId) {
        M5ReplaySession session = sessions.get(replayId);
        if (session == null) throw new IllegalArgumentException("Unknown replayId");
        return session;
    }

    public void close(String replayId) {
        M5ReplaySession session = sessions.remove(replayId);
        if (session != null) session.close();
    }
}
