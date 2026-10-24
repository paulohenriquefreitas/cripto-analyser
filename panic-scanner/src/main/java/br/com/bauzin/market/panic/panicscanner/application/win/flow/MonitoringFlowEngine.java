package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** One buffer per symbol, independent of the number or state of chart pivots. */
public final class MonitoringFlowEngine {
    public record Window(int seconds, long fromTimeMsc, long toTimeMsc, long events,
                         double buyVolume, double sellVolume, double unknownVolume, double delta,
                         Double buyPercent, Double sellPercent, Double unknownPercent,
                         Double lastDisplacement, String quality, String alert) {}
    public record TimeBasis(long feedTimeShiftMinutes, boolean confirmed,
                            long rejectedOld, long rejectedFuture, long rejectedRegression) {}
    public record Snapshot(String type, String symbol, long timeMsc, Long lastTimeMsc,
                           Double last, String volumeSource, List<Window> windows, TimeBasis timeBasis) {
        public Snapshot(String type, String symbol, long timeMsc, Long lastTimeMsc,
                        Double last, String volumeSource, List<Window> windows) {
            this(type, symbol, timeMsc, lastTimeMsc, last, volumeSource, windows, null);
        }
    }
    private final TradeFlowEngine flow = new TradeFlowEngine();
    private final NavigableMap<Long, Double> prices = new TreeMap<>();
    private final ArrayDeque<Long> invalidVolumes = new ArrayDeque<>();
    private final String symbol;
    private long firstTime = -1;
    private long lastTradeTime = -1;
    private long referenceTime = -1;
    private long lastSequence = -1;
    private long lastSequenceTime = -1;
    private long healthySince = -1;
    private boolean connected;

    public MonitoringFlowEngine(String symbol) { this.symbol = symbol; }

    public synchronized void connected(boolean value, long timeMsc) {
        if (value && !connected) healthySince = timeMsc;
        connected = value;
    }

    /** Source cursor timestamp + ordinal, not equality of trade contents. */
    public synchronized boolean onTrade(MarketTrade trade, long ordinal) {
        if (!symbol.equals(trade.symbol())) throw new IllegalArgumentException("monitor symbol mismatch");
        if (ordinal < 0) throw new IllegalArgumentException("invalid ordinal");
        if (trade.timeMsc() < lastSequenceTime
                || (trade.timeMsc() == lastSequenceTime && ordinal <= lastSequence)) return false;
        flow.onTrade(trade);
        lastSequenceTime = trade.timeMsc();
        lastSequence = ordinal;
        lastTradeTime = trade.timeMsc();
        onLast(trade.price(), trade.timeMsc());
        return true;
    }

    public synchronized void onLast(double price, long timeMsc) {
        if (!Double.isFinite(price) || price <= 0 || timeMsc < 0) throw new IllegalArgumentException("invalid LAST");
        firstTime = firstTime < 0 ? timeMsc : Math.min(firstTime, timeMsc);
        prices.put(timeMsc, price);
        referenceTime = Math.max(referenceTime, timeMsc);
        // Keep 60s and the preceding price for the exact left boundary.
        while (prices.size() > 1 && prices.higherKey(prices.firstKey()) <= referenceTime - 60_000) {
            prices.pollFirstEntry();
        }
    }

    public synchronized void invalidVolume(long timeMsc) { invalidVolumes.addLast(timeMsc); }

    public synchronized Snapshot snapshot(long timeMsc) {
        long now = Math.max(timeMsc, referenceTime);
        referenceTime = now;
        while (!invalidVolumes.isEmpty() && invalidVolumes.peekFirst() <= now - 30_000) invalidVolumes.removeFirst();
        var windows = List.of(window(Duration.ofSeconds(5), now), window(Duration.ofSeconds(10), now),
                window(Duration.ofSeconds(30), now));
        var latest = prices.floorEntry(now);
        return new Snapshot("FLOW", symbol, now, latest == null ? null : latest.getKey(),
                latest == null ? null : latest.getValue(), "MT5_VOLUME_REAL", windows);
    }

    private Window window(Duration duration, long now) {
        var value = flow.snapshotAt(duration, now);
        double total = value.totalVolume();
        Double unknown = total > 0 ? value.ambiguousVolume() * 100 / total : null;
        long from = now - duration.toMillis();
        var start = prices.floorEntry(from);
        var end = prices.floorEntry(now);
        // A stale boundary LAST cannot describe movement over this window reliably.
        Double displacement = start != null && end != null && from - start.getKey() <= 2_000
                && now - end.getKey() <= 2_000 ? end.getValue() - start.getValue() : null;
        String quality = !("WINV26".equals(symbol) || "WINZ26".equals(symbol)) ? "UNVALIDATED_SYMBOL"
                : !connected ? "DISCONNECTED"
                : invalidVolumes.stream().anyMatch(time -> time > from && time <= now) ? "INVALID_VOLUME"
                : total == 0 ? "NO_TRADES"
                : now - lastTradeTime > 2_000 ? "STALE"
                : firstTime < 0 || firstTime > from || healthySince > from ? "WARMING_UP"
                : unknown > 20 ? "HIGH_UNKNOWN"
                : displacement == null ? "PRICE_UNAVAILABLE" : "GOOD";
        // Descriptive anomaly only. Percentages below use total volume, including UNKNOWN.
        String alert = "GOOD".equals(quality) && value.knownVolume() >= 20
                && Math.max(value.buyShare(), value.sellShare()) >= 0.7 && Math.abs(displacement) <= 5
                ? "POSSIBLE_ABSORPTION" : null;
        return new Window((int) duration.toSeconds(), from, now, value.tradeCount(), value.buyVolume(),
                value.sellVolume(), value.ambiguousVolume(), value.knownDelta(),
                total == 0 ? null : value.buyVolume() * 100 / total,
                total == 0 ? null : value.sellVolume() * 100 / total, unknown, displacement, quality, alert);
    }
}
