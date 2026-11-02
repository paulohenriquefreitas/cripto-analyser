package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.application.win.strategy.ConfirmedPivotDetector;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Adapter over the existing Java detector, attached to the same live/replay pipeline. */
public final class ThreeCandleStructure implements ThreeCandlePatternDetector {
    public record Snapshot(List<StructuralLevel> levels, int totalActive, int maxLevels,
                           String selection, boolean supported, H1History h1History, H1SessionPreparation preparation,
                           List<StructuralLevel> confirmedPivots, List<DowAnticipationStudy.Candidate> earlyPivots) {}
    public record H1History(int previousSessions, boolean sufficient, String alignment) {}
    private final Map<Integer, ConfirmedPivotDetector> detectors = new HashMap<>(Map.of(
            5, new ConfirmedPivotDetector(), 60, new ConfirmedPivotDetector()));
    private final DowAnticipationStudy anticipation = new DowAnticipationStudy();
    private long m5Session = Long.MIN_VALUE;
    private final H1CandleAggregator hourly = new H1CandleAggregator();
    private final NavigableMap<Long, Integer> hourlySessions = new TreeMap<>();
    private final List<StructuralLevel> active = new ArrayList<>();
    private final int minutes;
    private H1SessionPreparation preparation;
    private long lastClosed = Long.MIN_VALUE;
    private double lastClose;
    public ThreeCandleStructure(int minutes) { this.minutes = minutes; }
    @Override public void onCandle(CandleUpdate update) {
        if (minutes != 5) return;
        if (update.closed()) {
            if (update.candle().bucketStartTimeMsc() <= lastClosed) return;
            lastClosed = update.candle().bucketStartTimeMsc(); lastClose = update.candle().close();
            anticipation.advance(update);
            process(update);
        }
        hourly.accept(update).ifPresent(this::process);
    }
    private void process(CandleUpdate update) {
        int timeframe = update.timeframeMinutes();
        var candle = update.candle();
        if (timeframe == 5) {
            long session = candle.bucketStartTimeMsc() / 86_400_000L;
            if (session != m5Session) {
                detectors.put(5, new ConfirmedPivotDetector());
                m5Session = session;
            }
        }
        if (timeframe == 60) hourlySessions.merge(candle.bucketStartTimeMsc() / 86_400_000L, 1, Integer::sum);
        active.replaceAll(level -> level.timeframeMinutes() == timeframe && level.state() == StructuralLevel.State.CONFIRMED
                && (timeframe != 5 || level.extremeTimeMsc() / 86_400_000L == m5Session)
                && level.confirmedAtMsc() <= update.availableAtMsc()
                && ConfirmedPivotDetector.isBroken(level.type() == StructuralLevel.Type.TOP, level.price(), candle.close())
                ? new StructuralLevel(level.id(), level.type(), level.price(), level.extremeTimeMsc(),
                    level.confirmedAtMsc(), level.region(), level.relevance(), StructuralLevel.State.BROKEN, update.availableAtMsc(), timeframe)
                : level);
        for (var pivot : detectors.get(timeframe).onCandleClosed(candle, update.availableAtMsc())) {
            var origin = pivot.origin();
            var region = new StructuralLevel.Region(origin.high(), origin.low(),
                    origin.low() + (origin.high()-origin.low()) / 2, "PIVOT_CANDLE");
            remember(List.of(new StructuralLevel(origin.symbol()+":"+timeframe+":"+origin.bucketStartTimeMsc()+":"+pivot.type(),
                    StructuralLevel.Type.valueOf(pivot.type().name()), pivot.price(), origin.bucketStartTimeMsc(), pivot.confirmedAtMsc(), region, "PROXIMITY", StructuralLevel.State.CONFIRMED, null, timeframe)));
        }
    }
    /** Restore only already confirmed memory when rebuilding the live candle feed. Never used by replay reset. */
    public void remember(List<StructuralLevel> levels) {
        for (var level : levels) {
            if (active.stream().noneMatch(existing -> existing.id().equals(level.id()))) active.add(level);
        }
    }
    public void prepare(H1SessionPreparation plan) {
        preparation=plan;
        remember(plan.candidates());
    }
    public List<StructuralLevel> activeLevels() { return List.copyOf(active); }
    private H1History h1History() {
        long currentDay = Math.max(0, lastClosed) / 86_400_000L;
        var previous = hourlySessions.headMap(currentDay, false);
        int sessions = previous.size();
        // Two previous trading sessions plus two left-side H1 bars before them.
        int warmup = sessions >= 2 ? previous.headMap(previous.navigableKeySet().stream()
                .skip(sessions-2L).findFirst().orElseThrow(), false).values().stream().mapToInt(Integer::intValue).sum() : 0;
        return new H1History(sessions, sessions >= 2 && warmup >= 2, "NATIVE_FEED_HOUR_NO_SHIFT");
    }
    public Snapshot snapshot(int maxLevels) {
        if (maxLevels < 1 || maxLevels > 12) throw new IllegalArgumentException("Max levels must be between 1 and 12");
        var order = Comparator.comparingDouble((StructuralLevel l) -> Math.abs(l.price()-lastClose))
                .thenComparing(Comparator.comparingLong(StructuralLevel::confirmedAtMsc).reversed())
                .thenComparing(Comparator.comparingLong(StructuralLevel::extremeTimeMsc).reversed())
                .thenComparing(StructuralLevel::id);
        var selected = new ArrayList<StructuralLevel>();
        // WIN contracts trade in five-point increments; collapse adjacent ticks visually only.
        double separation = active.stream().anyMatch(l -> l.id().startsWith("WIN")) ? 5.0 : 0.0;
        // Every confirmed M5 pivot of the selected session is visible, including broken levels.
        long selectedSession = preparation == null ? m5Session
                : java.time.LocalDate.parse(preparation.sessionDate()).toEpochDay();
        selected.addAll(active.stream().filter(l -> l.timeframeMinutes() == 5
                && l.extremeTimeMsc() / 86_400_000L == selectedSession)
                .sorted(Comparator.comparingLong(StructuralLevel::confirmedAtMsc).thenComparing(StructuralLevel::id)).toList());
        // H1 selection and preparation are intentionally unchanged.
        for (int timeframe : (preparation==null ? new int[]{60} : new int[]{})) {
            var group = new ArrayList<StructuralLevel>();
            for (var level : active.stream().filter(l -> l.timeframeMinutes() == timeframe).sorted(order).toList()) {
                if (group.stream().noneMatch(l -> Math.abs(l.price()-level.price()) <= separation)) group.add(level);
                if (group.size() == maxLevels) break;
            }
            selected.addAll(group);
        }
        if(preparation!=null) {
            selected.removeIf(l -> l.timeframeMinutes()==60);
            selected.addAll(preparation.levels());
        }
        return new Snapshot(List.copyOf(selected), active.size(), maxLevels,
                preparation==null ? "NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED" : preparation.criterion(),
                minutes == 5, h1History(), preparation,
                active.stream().filter(l -> l.timeframeMinutes() == 60
                        || l.extremeTimeMsc() / 86_400_000L == selectedSession).toList(),
                anticipation.snapshot().stream().filter(p->p.origin().candle().bucketStartTimeMsc()/86_400_000L==selectedSession).toList());
    }
}
