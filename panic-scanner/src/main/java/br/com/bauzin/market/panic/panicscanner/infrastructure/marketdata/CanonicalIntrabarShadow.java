package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import java.util.List;
import java.util.Objects;

/** Standalone opt-in shadow session. Never publishes to the frontend or changes its authority. */
public final class CanonicalIntrabarShadow {
    private final String symbol;
    private final Ta4jMt5Sma9Adapter legacy = new Ta4jMt5Sma9Adapter();
    private final IntrabarM5Processor canonical = new IntrabarM5Processor();
    private long samples, divergences, unavailable;

    public CanonicalIntrabarShadow(String symbol, List<Mt5Candle> initial) {
        this.symbol = CanonicalPriceEvent.normalizeSymbol(symbol);
        if (initial.isEmpty()) throw new IllegalArgumentException("Shadow needs official baseline");
        // Both paths start with exactly the same official prefix and current partial bar.
        var current = Mt5CanonicalPriceMapper.candle(symbol, initial.getLast());
        canonical.warmUp(symbol, current.bucketStartTimeMsc(), initial.subList(0, initial.size() - 1).stream()
                .map(c -> Mt5CanonicalPriceMapper.candle(symbol, c)).toList());
        canonical.seedCurrentCandle(current);
        legacy.synchronize(initial);
    }

    public record Comparison(long timeMsc, IntrabarCandleSnapshot oldCandle,
                             IntrabarMarketState canonical, Ta4jMt5Sma9Adapter.Current oldAverages,
                             boolean equivalent) {}

    public Comparison onTick(Mt5Tick tick) {
        var event = Mt5CanonicalPriceMapper.liveSnapshot(symbol, tick);
        var state = canonical.onEvent(event);
        var old = legacy.onTick(tick);
        var oldCandle = legacy.candleSnapshot(symbol);
        boolean equal = Objects.equals(oldCandle, state.candle())
                && old != null && Objects.equals(old.value(), state.sma9())
                && Objects.equals(old.sma21(), state.sma21());
        samples++;
        if (old == null) unavailable++;
        if (!equal) divergences++;
        return new Comparison(tick.timeMsc(), oldCandle, state, old, equal);
    }

    /** Resynchronizes only the old authority; never overwrites reconstructed canonical bars. */
    public void synchronizeLegacy(List<Mt5Candle> official) { legacy.synchronize(official); }
    public long samples() { return samples; }
    public long divergences() { return divergences; }
    public long legacyUnavailable() { return unavailable; }

    public static void main(String[] args) throws Exception {
        int seconds = args.length == 0 ? 30 : Integer.parseInt(args[0]);
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Duration must be 1..3600 seconds");
        var client = new Mt5ProcessClient();
        var shadow = new CanonicalIntrabarShadow("WINV26", client.readCandles());
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        long firstTime = -1, lastTime = -1, lastLog = 0;
        // Use the actual existing polling client without modifying its producer or lifecycle.
        try (var stream = new Mt5TickStreamClient()) {
            var queue = new java.util.concurrent.ArrayBlockingQueue<Mt5Tick>(4096);
            var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try { stream.start(tick -> {
                    if (!queue.offer(tick)) throw new IllegalStateException("Shadow consumer overflow; abort comparison");
                }); }
                catch (Exception ex) { failure.set(ex); }
            });
            try {
                while (System.nanoTime() < deadline) {
                    if (failure.get() != null) throw failure.get();
                    var tick = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (tick == null) continue;
                    if (firstTime < 0) firstTime = tick.timeMsc();
                    lastTime = tick.timeMsc();
                    var comparison = shadow.onTick(tick);
                    long now = System.nanoTime();
                    if (!comparison.equivalent() && now - lastLog >= 1_000_000_000L) {
                        System.out.println("[CANONICAL-COMPARE] " + comparison);
                        lastLog = now;
                    }
                    if (comparison.oldAverages() == null) shadow.synchronizeLegacy(client.readCandles());
                }
                if (failure.get() != null) throw failure.get();
            } finally {
                stream.stop();
                worker.join(6000);
            }
        }
        System.out.printf("[CANONICAL-COMPARE] samples=%d divergences=%d legacyUnavailable=%d "
                        + "firstTimeMsc=%d lastTimeMsc=%d activeMarketEvidence=%s%n",
                shadow.samples(), shadow.divergences(), shadow.legacyUnavailable(), firstTime, lastTime,
                lastTime > firstTime);
    }
}
