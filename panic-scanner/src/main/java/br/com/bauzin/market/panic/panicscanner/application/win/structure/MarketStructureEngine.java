package br.com.bauzin.market.panic.panicscanner.application.win.structure;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure event-time engine. One instance accepts one symbol until reset. */
public final class MarketStructureEngine {
    private final Map<PriceReference, MovingAverageTracker> trackers = new EnumMap<>(PriceReference.class);
    private String symbol;
    private long lastTimeMsc = -1;

    public MarketStructureEngine() {
        this(MarketStructureConfig.defaults());
    }

    public MarketStructureEngine(MarketStructureConfig config) {
        Objects.requireNonNull(config, "config");
        for (PriceReference reference : PriceReference.values()) {
            trackers.put(reference, new MovingAverageTracker(reference, config));
        }
    }

    public synchronized MarketStructureUpdate onSample(MarketStructureSample sample) {
        Objects.requireNonNull(sample, "sample");
        String normalized = sample.symbol().toUpperCase(Locale.ROOT);
        if (symbol != null && !symbol.equals(normalized)) {
            throw new IllegalArgumentException("Cannot mix symbols without reset: " + symbol + " and " + normalized);
        }
        if (sample.timeMsc() < lastTimeMsc) {
            throw new IllegalArgumentException("Market structure sample out of order: "
                    + sample.timeMsc() + " < " + lastTimeMsc);
        }
        symbol = normalized;
        List<MarketStructureEvent> events = new ArrayList<>();
        List<MovingAverageInteraction> completed = new ArrayList<>();
        MovingAverageSnapshot sma9 = trackers.get(PriceReference.SMA9).update(sample, events, completed);
        MovingAverageSnapshot sma21 = trackers.get(PriceReference.SMA21).update(sample, events, completed);
        lastTimeMsc = sample.timeMsc();
        return new MarketStructureUpdate(sma9, sma21, events, completed);
    }

    public synchronized Optional<MovingAverageSnapshot> snapshot(PriceReference reference) {
        return Optional.ofNullable(trackers.get(Objects.requireNonNull(reference)).snapshot());
    }

    public synchronized Optional<MovingAverageInteraction> lastCompleted(PriceReference reference) {
        return Optional.ofNullable(trackers.get(Objects.requireNonNull(reference)).lastCompleted());
    }

    public synchronized void reset() {
        symbol = null;
        lastTimeMsc = -1;
        trackers.values().forEach(MovingAverageTracker::reset);
    }
}
