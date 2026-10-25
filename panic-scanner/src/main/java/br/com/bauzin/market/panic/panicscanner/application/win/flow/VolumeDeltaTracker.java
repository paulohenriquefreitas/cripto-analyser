package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

/** Event-time sidecar. One writer (the owning engine); readers use immutable volatile results.
 * delta is rolling, cumulativeDelta is session CVD. No price-based aggression inference.
 */
public final class VolumeDeltaTracker {
    public enum Quality { GOOD, NO_TRADES, INVALID_VOLUME, CAPACITY_EXCEEDED }
    public record FlowResult(long timeMsc, long windowMillis, long buyVolume, long sellVolume,
                             long ambiguousVolume, long delta, long cumulativeDelta,
                             int events, long processedTicks, Quality quality, boolean complete,
                             boolean cumulativeComplete) {}

    private final long windowMillis;
    private final long[] timestamps;
    private final long[] volumes;
    private final AggressorSide[] sides;
    private int head, size, invalid;
    private long buy, sell, ambiguous, cumulative, processed;
    private long referenceTime = -1;
    private long overflowThrough = -1;
    private long lastEventTime = -1;
    private boolean cumulativeComplete = true;
    private volatile FlowResult latest;

    public VolumeDeltaTracker(long windowMillis, int capacity) {
        if (windowMillis <= 0 || capacity <= 0) throw new IllegalArgumentException("Positive window/capacity required");
        this.windowMillis = windowMillis;
        timestamps = new long[capacity];
        volumes = new long[capacity];
        sides = new AggressorSide[capacity];
    }

    public FlowResult appendTick(MarketTrade event, long currentMsc) {
        // Contracts are integral. Never silently truncate fractional or overflowing volume.
        double rawVolume = event.volume();
        long volume = rawVolume < 0x1p63 && rawVolume == Math.rint(rawVolume) ? (long) rawVolume : 0;
        return append(event.timeMsc(), volume, event.side(), currentMsc);
    }

    public FlowResult invalidVolume(long timeMsc, long currentMsc) {
        return append(timeMsc, 0, AggressorSide.AMBIGUOUS, currentMsc);
    }

    private FlowResult append(long timeMsc, long volume, AggressorSide side, long currentMsc) {
        if (timeMsc < 0 || currentMsc < timeMsc) throw new IllegalArgumentException("Invalid event/reference time");
        if (timeMsc < lastEventTime) throw new IllegalArgumentException("Regressing trade time");
        lastEventTime = timeMsc;
        if (volume == 0) cumulativeComplete = false;
        long now = Math.max(referenceTime, currentMsc);
        expire(now);
        cumulative = Math.addExact(cumulative, side == AggressorSide.BUY ? volume : side == AggressorSide.SELL ? -volume : 0);
        processed++;
        // Late source events outside the rolling interval still belong to session CVD.
        if (timeMsc > now-windowMillis) {
            if (size == timestamps.length) {
                overflowThrough = Math.max(overflowThrough, timestamps[head]);
                removeFirst();
            }
            int index = (head+size) % timestamps.length;
            timestamps[index] = timeMsc;
            volumes[index] = volume;
            sides[index] = side;
            size++;
            if (volume == 0) invalid++;
            else if (side == AggressorSide.BUY) buy = Math.addExact(buy, volume);
            else if (side == AggressorSide.SELL) sell = Math.addExact(sell, volume);
            else ambiguous = Math.addExact(ambiguous, volume);
        }
        return result(now);
    }

    public FlowResult snapshot(long currentMsc) {
        if (currentMsc < 0) throw new IllegalArgumentException("Invalid reference time");
        long now = Math.max(referenceTime, currentMsc);
        expire(now);
        return result(now);
    }

    public FlowResult latest() { return latest; }

    private void expire(long now) {
        referenceTime = now;
        while (size > 0 && timestamps[head] <= now-windowMillis) removeFirst();
    }

    private void removeFirst() {
        long volume = volumes[head];
        if (volume == 0) invalid--;
        else if (sides[head] == AggressorSide.BUY) buy -= volume;
        else if (sides[head] == AggressorSide.SELL) sell -= volume;
        else ambiguous -= volume;
        head = (head+1) % timestamps.length;
        size--;
    }

    private FlowResult result(long now) {
        boolean overflow = overflowThrough > now-windowMillis;
        Quality quality = overflow ? Quality.CAPACITY_EXCEEDED : invalid > 0 ? Quality.INVALID_VOLUME
                : size == 0 ? Quality.NO_TRADES : Quality.GOOD;
        latest = new FlowResult(now, windowMillis, buy, sell, ambiguous, Math.subtractExact(buy, sell),
                cumulative, size, processed, quality, !overflow && invalid == 0, cumulativeComplete);
        return latest;
    }
}
