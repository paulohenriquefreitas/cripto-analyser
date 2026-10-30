package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.time.LocalDate;
import java.util.*;

/** Experimental closed-M5 projection. Never supplies pivots, trading state or Fibonacci inputs. */
public final class OpeningStructureContext {
    private static final long M5 = 300_000L, DAY = 86_400_000L;
    public record Reference(double price, long formationTimeMsc, long availableAtMsc, String source) {}
    public record PreviousSession(String date, Reference close, Reference high, Reference low,
                                  Reference officialSettlement, String coverage, String settlementStatus) {}
    public record Gap(String kind, double points, Reference from, Reference to, long availableAtMsc) {}
    public record Leg(String direction, String state, Reference origin, Reference destination,
                      double amplitudePoints, long availableAtMsc, String intrabarOrder) {}
    public record Window(int minutes, Long availableAtMsc, Double amplitudePoints, String status) {}
    public record Threshold(int minimumPoints, boolean reached, Long firstReachedAtMsc) {}
    public record Snapshot(String symbol, String sessionDate, long availableAtMsc, String state,
                           String unit, String timeBasis, PreviousSession previousSession,
                           Reference tradedOpen, Gap gap, Reference observedHigh, Reference observedLow,
                           Leg initialLeg, List<Leg> legHistory, List<Window> windows,
                           List<Threshold> thresholds, String coverage) {}
    private final String symbol;
    private final String sessionDate;
    private final NavigableMap<Long, CandleUpdate> closed = new TreeMap<>();
    private long available = -1;
    public OpeningStructureContext(String symbol, String sessionDate) {
        this.symbol = Objects.requireNonNull(symbol);
        this.sessionDate = LocalDate.parse(sessionDate).toString();
    }
    public void advance(List<CandleUpdate> input, long cutoff) {
        if (cutoff < available) throw new IllegalArgumentException("Rebuild opening for a regressing cursor");
        for (var bar : known(symbol, input, cutoff)) {
            var prior = closed.putIfAbsent(bar.candle().bucketStartTimeMsc(), bar);
            if (prior != null && !prior.equals(bar)) throw new IllegalArgumentException("Conflicting closed M5");
        }
        available = cutoff;
    }
    public Snapshot snapshot() { return analyze(symbol, sessionDate, List.copyOf(closed.values()), available); }
    private static List<CandleUpdate> known(String symbol, List<CandleUpdate> input, long cutoff) {
        return input.stream().filter(b -> b.closed() && b.timeframeMinutes() == 5
                && b.candle().symbol().equals(symbol) && b.availableAtMsc() <= cutoff
                && b.availableAtMsc() >= b.candle().bucketStartTimeMsc() + M5)
            .sorted(Comparator.comparingLong(b -> b.candle().bucketStartTimeMsc())).toList();
    }
    private static Reference ref(CandleUpdate b, double price, String source) {
        return new Reference(price, b.candle().bucketStartTimeMsc(), b.availableAtMsc(), source);
    }
    public static Snapshot analyze(String symbol, String sessionDate, List<CandleUpdate> input, long cutoff) {
        long day = LocalDate.parse(sessionDate).toEpochDay(), opening = day * DAY + 9 * 3_600_000L;
        var known = new TreeMap<Long,CandleUpdate>();
        for (var b : known(symbol,input,cutoff)) {
            var prior = known.putIfAbsent(b.candle().bucketStartTimeMsc(),b);
            if (prior != null && !prior.equals(b)) throw new IllegalArgumentException("Conflicting closed M5");
        }
        var previous = known.values().stream().filter(b -> b.candle().bucketStartTimeMsc()/DAY < day).toList();
        PreviousSession prior = null;
        if (!previous.isEmpty()) {
            long previousDay = previous.getLast().candle().bucketStartTimeMsc()/DAY;
            var bars = previous.stream().filter(b -> b.candle().bucketStartTimeMsc()/DAY == previousDay).toList();
            var high = bars.stream().max(Comparator.comparingDouble(b -> b.candle().high())).orElseThrow();
            var low = bars.stream().min(Comparator.comparingDouble(b -> b.candle().low())).orElseThrow();
            long knownAt = bars.getLast().availableAtMsc();
            prior = new PreviousSession(LocalDate.ofEpochDay(previousDay).toString(),
                ref(bars.getLast(),bars.getLast().candle().close(),"MT5_LAST_OBSERVED_M5_CLOSE"),
                new Reference(high.candle().high(),high.candle().bucketStartTimeMsc(),knownAt,"MT5_OBSERVED_PREVIOUS_SESSION_M5_HIGH"),
                new Reference(low.candle().low(),low.candle().bucketStartTimeMsc(),knownAt,"MT5_OBSERVED_PREVIOUS_SESSION_M5_LOW"),
                null,"OBSERVED_M5_ONLY_NOT_CERTIFIED_SESSION_CLOSE","UNAVAILABLE_NO_OFFICIAL_SOURCE");
        }
        var bars = known.values().stream().filter(b -> b.candle().bucketStartTimeMsc() >= opening
                && b.candle().bucketStartTimeMsc()/DAY == day).toList();
        boolean hasOpening = !bars.isEmpty() && bars.getFirst().candle().bucketStartTimeMsc() == opening;
        Reference tradedOpen = hasOpening ? ref(bars.getFirst(),bars.getFirst().candle().open(),"MT5_FIRST_CLOSED_M5_OPEN") : null;
        Gap gap = tradedOpen == null || prior == null ? null : new Gap("GAP",tradedOpen.price()-prior.close().price(),
            prior.close(),tradedOpen,Math.max(tradedOpen.availableAtMsc(),prior.close().availableAtMsc()));
        Reference high = null, low = null;
        var history = new ArrayList<Leg>();
        String direction = null;
        for (var b : bars) {
            if (high == null || b.candle().high() > high.price()) high = ref(b,b.candle().high(),"MT5_CLOSED_M5_HIGH");
            if (low == null || b.candle().low() < low.price()) low = ref(b,b.candle().low(),"MT5_CLOSED_M5_LOW");
            if (!hasOpening) continue;
            // Freeze the first non-flat close relative to the traded open as a hypothesis, not a pivot.
            if (direction == null && b.candle().close() != tradedOpen.price())
                direction = b.candle().close() > tradedOpen.price() ? "UP" : "DOWN";
            if (direction == null) continue;
            var first = bars.getFirst();
            boolean up = direction.equals("UP");
            var origin = ref(first,up ? first.candle().low() : first.candle().high(),"MT5_OPENING_M5_OBSERVED_EXTREME");
            var destination = up ? high : low;
            var leg = new Leg(direction,"EM_FORMACAO",origin,destination,
                Math.abs(destination.price()-origin.price()),b.availableAtMsc(),
                origin.formationTimeMsc() == destination.formationTimeMsc() ? "UNKNOWN_SAME_M5" : "ORDERED_M5_BARS");
            history.add(leg); // All closed-bar versions retained, including intermediate counter-movements.
        }
        var windows = new ArrayList<Window>();
        for (int minutes : new int[]{15,30,45}) {
            long end = opening + minutes*60_000L;
            var window = bars.stream().filter(b -> b.candle().bucketStartTimeMsc() < end).toList();
            boolean complete = window.size() == minutes/5;
            for (int i=0;i<window.size();i++) complete &= window.get(i).candle().bucketStartTimeMsc() == opening+i*M5;
            windows.add(new Window(minutes,window.isEmpty()?null:window.getLast().availableAtMsc(),
                window.isEmpty()?null:window.stream().mapToDouble(b -> b.candle().high()).max().orElseThrow()
                    -window.stream().mapToDouble(b -> b.candle().low()).min().orElseThrow(),
                complete ? "COMPLETE" : cutoff < end ? "EM_FORMACAO" : "INCOMPLETE"));
        }
        var thresholds = new ArrayList<Threshold>();
        for (int minimum : new int[]{0,250,300}) {
            var reached = history.stream().filter(l -> l.amplitudePoints() >= minimum).findFirst();
            thresholds.add(new Threshold(minimum,reached.isPresent(),reached.map(Leg::availableAtMsc).orElse(null)));
        }
        boolean contiguous = hasOpening;
        for (int i=0;i<bars.size();i++) contiguous &= bars.get(i).candle().bucketStartTimeMsc() == opening+i*M5;
        return new Snapshot(symbol,sessionDate,cutoff,!hasOpening ? "UNAVAILABLE" : history.isEmpty() ? "WAITING_DIRECTION" : "EM_FORMACAO",
            "WIN_INDEX_POINTS","MT5_WALL_CLOCK",prior,tradedOpen,gap,high,low,
            history.isEmpty()?null:history.getLast(),List.copyOf(history),List.copyOf(windows),List.copyOf(thresholds),
            !hasOpening ? "MISSING_0900" : contiguous ? "CONTIGUOUS_CLOSED_M5" : "MISSING_M5");
    }
    public static Snapshot analyze(String symbol, List<CandleUpdate> candles, ThreeCandleStructure.Snapshot structure, long cutoff) {
        String day = structure.preparation() != null ? structure.preparation().sessionDate()
            : LocalDate.ofEpochDay(cutoff/DAY).toString();
        return analyze(symbol,day,candles,cutoff);
    }
}
