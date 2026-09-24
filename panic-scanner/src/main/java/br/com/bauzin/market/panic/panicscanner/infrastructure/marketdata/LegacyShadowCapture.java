package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.ShadowObservation;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Passive, bounded and nonblocking. Losing diagnostic data never interrupts LIVE. */
public final class LegacyShadowCapture {
    static final int CAPACITY = 20_000;
    private volatile Session active;

    private static final class Session {
        final String token = UUID.randomUUID().toString();
        final ConcurrentLinkedQueue<ShadowObservation> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        final AtomicLong dropped = new AtomicLong();
        volatile long expires = System.nanoTime() + 30_000_000_000L;
        volatile int maximum;
    }

    public synchronized String open() {
        if (enabled()) throw new IllegalStateException("A shadow capture is already active");
        active = new Session();
        return active.token;
    }

    public boolean enabled() {
        Session session = active;
        return session != null && System.nanoTime() < session.expires;
    }

    public void offer(ShadowObservation observation) {
        Session session = active;
        if (session == null || System.nanoTime() >= session.expires) return;
        int count = session.size.incrementAndGet();
        if (count > CAPACITY) {
            session.size.decrementAndGet();
            session.dropped.incrementAndGet();
            return;
        }
        session.maximum = Math.max(session.maximum, count);
        session.queue.offer(observation);
    }

    public record Batch(List<ShadowObservation> observations, long dropped, int queued, int maxQueued) {}

    public Batch poll(String token) {
        Session session = require(token);
        session.expires = System.nanoTime() + 30_000_000_000L;
        List<ShadowObservation> result = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            var observation = session.queue.poll();
            if (observation == null) break;
            session.size.decrementAndGet();
            result.add(observation);
        }
        return new Batch(List.copyOf(result), session.dropped.get(), session.size.get(), session.maximum);
    }

    public synchronized void close(String token) { require(token); active = null; }

    private Session require(String token) {
        Session session = active;
        if (session == null || !session.token.equals(token) || System.nanoTime() >= session.expires)
            throw new IllegalArgumentException("Unknown or expired shadow session");
        return session;
    }
}
