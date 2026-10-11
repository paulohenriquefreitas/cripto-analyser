package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** Sequential local-only research. Retains excursion samples, never historical LASTs. */
public final class Pullback09BatchPeriodRunner {
    public enum Direction { UP, DOWN, TOTAL }
    public record Threshold(int points, long reached, double percentage) {}
    public record Window(Direction direction, int minutes, long complete, long incomplete,
                         Double meanMfe, Double medianMfe, Double meanMae, Double medianMae,
                         List<Threshold> thresholds) {
        public Window { thresholds = List.copyOf(thresholds); }
    }
    public record Session(LocalDate date, long lasts, long up, long down, long complete, long incomplete) {}
    public record Result(String symbol, LocalDate start, LocalDate end, long lasts, long elapsedNanos,
                         Map<Pullback09Setup.EventType, Long> events, List<Session> sessions, List<Window> windows) {
        public Result {
            events = Map.copyOf(events);
            sessions = List.copyOf(sessions);
            windows = List.copyOf(windows);
        }
        public long complete() { return sessions.stream().mapToLong(Session::complete).sum(); }
        public long incomplete() { return sessions.stream().mapToLong(Session::incomplete).sum(); }
        public double seconds() { return elapsedNanos / 1_000_000_000.0; }
        public double lastsPerSecond() { return elapsedNanos == 0 ? 0 : lasts / seconds(); }
    }

    private final Pullback09BatchRunner daily;
    public Pullback09BatchPeriodRunner() { this(new Pullback09BatchRunner()); }
    public Pullback09BatchPeriodRunner(String template) { this(new Pullback09BatchRunner(template)); }
    public Pullback09BatchPeriodRunner(Pullback09BatchRunner daily) { this.daily = Objects.requireNonNull(daily); }

    public Result run(String symbol, LocalDate start, LocalDate end) throws IOException {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid period: start must be <= end");
        }
        if (symbol == null || !(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}"))) throw new IllegalArgumentException("Invalid symbol");
        long began = System.nanoTime();
        long lasts = 0;
        var events = new EnumMap<Pullback09Setup.EventType, Long>(Pullback09Setup.EventType.class);
        for (var type : Pullback09Setup.EventType.values()) events.put(type, 0L);
        var sessions = new ArrayList<Session>();
        var samples = new EnumMap<Direction, List<Samples>>(Direction.class);
        for (var direction : Direction.values()) samples.put(direction, List.of(new Samples(), new Samples(), new Samples()));
        for (LocalDate date = start;; date = date.plusDays(1)) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Period interrupted before " + date);
            try {
                var day = daily.run(symbol, date);
                lasts += day.lastsProcessed();
                for (var type : Pullback09Setup.EventType.values()) events.merge(type, day.count(type), Long::sum);
                sessions.add(new Session(date, day.lastsProcessed(), day.count(Pullback09Setup.EventType.PULLB09_UP),
                        day.count(Pullback09Setup.EventType.PULLB09_DOWN), day.completeOutcomes(),
                        day.records().size() - day.completeOutcomes()));
                for (var record : day.records()) {
                    Direction direction = switch (record.snapshot().setupType()) {
                        case PULLB09_UP -> Direction.UP;
                        case PULLB09_DOWN -> Direction.DOWN;
                        default -> throw new IllegalStateException("Unexpected research setup type");
                    };
                    add(samples.get(direction), record.outcome());
                    add(samples.get(Direction.TOTAL), record.outcome());
                }
            } catch (IOException | RuntimeException e) {
                throw new IOException("FAILED " + symbol + " " + date + ": " + e.getMessage()
                        + "; period report not produced", e);
            }
            if (date.equals(end)) break;
        }
        var windows = new ArrayList<Window>();
        int[] minutes = {5, 15, 30};
        for (var direction : Direction.values()) {
            for (int i = 0; i < minutes.length; i++) windows.add(samples.get(direction).get(i).finish(direction, minutes[i]));
        }
        return new Result(symbol, start, end, lasts, System.nanoTime() - began, events, sessions, windows);
    }

    private static void add(List<Samples> samples, Pullback09SetupOutcome outcome) {
        samples.get(0).add(outcome.complete5m(), outcome.mfe5m(), outcome.mae5m());
        samples.get(1).add(outcome.complete15m(), outcome.mfe15m(), outcome.mae15m());
        samples.get(2).add(outcome.complete30m(), outcome.mfe30m(), outcome.mae30m());
    }

    private static final class Samples {
        final List<Double> mfe = new ArrayList<>();
        final List<Double> mae = new ArrayList<>();
        long incomplete;
        void add(boolean complete, Double favorable, Double adverse) {
            if (!complete) { incomplete++; return; }
            if (favorable == null || adverse == null || !Double.isFinite(favorable) || !Double.isFinite(adverse)) {
                throw new IllegalStateException("COMPLETE outcome has invalid MFE/MAE");
            }
            mfe.add(favorable);
            mae.add(adverse);
        }
        Window finish(Direction direction, int minutes) {
            Collections.sort(mfe);
            Collections.sort(mae);
            var thresholds = new ArrayList<Threshold>();
            for (int points : new int[]{50, 100, 150, 200, 300}) {
                long reached = mfe.stream().filter(value -> value >= points).count();
                thresholds.add(new Threshold(points, reached, mfe.isEmpty() ? 0 : 100.0 * reached / mfe.size()));
            }
            return new Window(direction, minutes, mfe.size(), incomplete, mean(mfe), median(mfe), mean(mae), median(mae), thresholds);
        }
        static Double mean(List<Double> values) {
            return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        }
        static Double median(List<Double> sorted) {
            int n = sorted.size();
            return n == 0 ? null : n % 2 == 1 ? sorted.get(n / 2) : sorted.get(n / 2 - 1) / 2 + sorted.get(n / 2) / 2;
        }
    }
}
