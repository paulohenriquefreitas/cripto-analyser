package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;

import java.util.*;

/**
 * Causal evaluator that tracks SETUP OUTCOME, not trade, price excursions (MFE / MAE)
 * over strictly posterior LAST events in 5m, 15m, and 30m windows.
 */
public final class Pullback09SetupOutcomeTracker {
    public static final long DURATION_5M_MSC = 5 * 60 * 1000L;
    public static final long DURATION_15M_MSC = 15 * 60 * 1000L;
    public static final long DURATION_30M_MSC = 30 * 60 * 1000L;

    private final Map<String, Pullback09ResearchSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, WindowTracker> trackers = new LinkedHashMap<>();
    private long lastTimeMsc = -1;
    private boolean finished;

    public synchronized void register(Pullback09ResearchSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (finished) throw new IllegalStateException("Research already finished");
        if (snapshots.containsKey(snapshot.eventId())) return;
        snapshots.put(snapshot.eventId(), snapshot);
        trackers.put(snapshot.eventId(), new WindowTracker(snapshot.setupAvailableTimeMsc(), snapshot.referencePrice()));
    }

    public synchronized void onPrice(CanonicalPriceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (finished) return;
        long timeMsc = event.timeMsc();
        if (timeMsc < lastTimeMsc) throw new IllegalArgumentException("LASTs must be chronological");
        lastTimeMsc = timeMsc;
        double price = event.price();
        for (WindowTracker tracker : trackers.values()) {
            tracker.accept(timeMsc, price);
        }
    }

    public synchronized void finish() {
        finished = true;
        for (WindowTracker tracker : trackers.values()) {
            tracker.finish();
        }
    }

    public synchronized List<Pullback09ResearchRecord> records() {
        List<Pullback09ResearchRecord> records = new ArrayList<>(snapshots.size());
        for (Map.Entry<String, Pullback09ResearchSnapshot> entry : snapshots.entrySet()) {
            String eventId = entry.getKey();
            Pullback09ResearchSnapshot snapshot = entry.getValue();
            WindowTracker tracker = trackers.get(eventId);
            Pullback09SetupOutcome outcome = tracker != null ? tracker.outcome(eventId) : null;
            records.add(new Pullback09ResearchRecord(snapshot, outcome));
        }
        return List.copyOf(records);
    }

    public synchronized Optional<Pullback09SetupOutcome> outcomeFor(String eventId) {
        WindowTracker tracker = trackers.get(eventId);
        return tracker != null ? Optional.of(tracker.outcome(eventId)) : Optional.empty();
    }

    private static final class WindowTracker {
        private final long setupAvailableTimeMsc;
        private final double referencePrice;

        private double max5 = Double.NEGATIVE_INFINITY;
        private double min5 = Double.POSITIVE_INFINITY;
        private boolean complete5 = false;

        private double max15 = Double.NEGATIVE_INFINITY;
        private double min15 = Double.POSITIVE_INFINITY;
        private boolean complete15 = false;

        private double max30 = Double.NEGATIVE_INFINITY;
        private double min30 = Double.POSITIVE_INFINITY;
        private boolean complete30 = false;

        WindowTracker(long setupAvailableTimeMsc, double referencePrice) {
            this.setupAvailableTimeMsc = setupAvailableTimeMsc;
            this.referencePrice = referencePrice;
        }

        void accept(long timeMsc, double price) {
            // Requirement 3 & 7-C: strictly subsequent LASTs after setupAvailableTimeMsc
            if (timeMsc <= setupAvailableTimeMsc) return;

            long elapsed = timeMsc - setupAvailableTimeMsc;

            if (elapsed <= DURATION_5M_MSC) {
                max5 = Math.max(max5, price);
                min5 = Math.min(min5, price);
            }
            if (elapsed >= DURATION_5M_MSC) {
                complete5 = true;
            }

            if (elapsed <= DURATION_15M_MSC) {
                max15 = Math.max(max15, price);
                min15 = Math.min(min15, price);
            }
            if (elapsed >= DURATION_15M_MSC) {
                complete15 = true;
            }

            if (elapsed <= DURATION_30M_MSC) {
                max30 = Math.max(max30, price);
                min30 = Math.min(min30, price);
            }
            if (elapsed >= DURATION_30M_MSC) {
                complete30 = true;
            }
        }

        void finish() {
            // Closed/finished without extrapolating.
        }

        Pullback09SetupOutcome outcome(String eventId) {
            Double mfe5 = (complete5 && Double.isFinite(max5)) ? (max5 - referencePrice) : null;
            Double mae5 = (complete5 && Double.isFinite(min5)) ? (referencePrice - min5) : null;

            Double mfe15 = (complete15 && Double.isFinite(max15)) ? (max15 - referencePrice) : null;
            Double mae15 = (complete15 && Double.isFinite(min15)) ? (referencePrice - min15) : null;

            Double mfe30 = (complete30 && Double.isFinite(max30)) ? (max30 - referencePrice) : null;
            Double mae30 = (complete30 && Double.isFinite(min30)) ? (referencePrice - min30) : null;

            return new Pullback09SetupOutcome(
                    eventId,
                    complete5, mfe5, mae5,
                    complete15, mfe15, mae15,
                    complete30, mfe30, mae30
            );
        }
    }
}
