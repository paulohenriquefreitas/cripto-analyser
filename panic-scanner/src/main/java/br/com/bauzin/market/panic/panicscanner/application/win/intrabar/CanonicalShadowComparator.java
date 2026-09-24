package br.com.bauzin.market.panic.panicscanner.application.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import java.util.*;

/** Single-owner, event-time as-of join. Never reads two mutable engines on different threads. */
public final class CanonicalShadowComparator {
    public static final double SMA_TOLERANCE = 1e-8; // absolute index points; OHLC/price exact
    public static final int MAX_STATES = 100_000, MAX_EVIDENCE = 100, CONTEXT = 16;
    public enum Readiness { SHADOW_NOT_READY, SHADOW_COMPARABLE }
    public enum Difference {
        PRICE_MISMATCH, BUCKET_MISMATCH, OPEN_MISMATCH, HIGH_MISMATCH, LOW_MISMATCH, CLOSE_MISMATCH,
        SMA9_MISMATCH, SMA21_MISMATCH, MISSING_OLD_STATE, MISSING_CANONICAL_STATE, ORDER_ERROR, SOURCE_GAP
    }
    public record Evidence(String phase, long bucket, long timeMsc, Set<Difference> differences,
                           ShadowObservation old, IntrabarMarketState canonical, IntrabarMarketState official,
                           List<CanonicalPriceEvent> context) {}
    public record ClosedComparison(long bucket, boolean completeSessionBucket,
                                   ShadowObservation old, IntrabarMarketState canonical, IntrabarMarketState official,
                                   Set<Difference> oldVsCanonical, Set<Difference> canonicalVsOfficial,
                                   Set<Difference> oldVsOfficial) {}
    private final List<ClosedComparison> closedComparisons = new ArrayList<>();
    private record Group(IntrabarMarketState state, int count) {}
    private final NavigableMap<Long, Group> states = new TreeMap<>();
    private final ArrayDeque<ShadowObservation> pending = new ArrayDeque<>();
    private final ArrayDeque<CanonicalPriceEvent> context = new ArrayDeque<>();
    private final Map<Long, IntrabarMarketState> finalCanonical = new TreeMap<>();
    private final Map<Long, ShadowObservation> finalOld = new TreeMap<>();
    private final Set<Long> finalized = new HashSet<>();
    private final Map<Long, CanonicalPriceEvent[]> extremes = new HashMap<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private final EnumMap<Difference, Long> intrabar = new EnumMap<>(Difference.class);
    private final EnumMap<Difference, Long> closedOld = new EnumMap<>(Difference.class);
    private final EnumMap<Difference, Long> closedOfficial = new EnumMap<>(Difference.class);
    private final EnumMap<Difference, Long> oldOfficial = new EnumMap<>(Difference.class);
    private long watermark = -1, oldTime = -1, comparisonStart = Long.MAX_VALUE;
    private long comparisons, skipped, ambiguous, canonicalEvents, bucketsObserved, bucketsCompared, fullThreeWayComparisons, completeSessionBuckets;
    private long firstCompared = -1, lastCompared = -1;
    private boolean initialized;
    private IntrabarMarketState latest;
    private Readiness readiness = Readiness.SHADOW_NOT_READY;

    public void initialize(long comparisonStart) {
        if (initialized) throw new IllegalStateException("New comparator required per session");
        initialized = true; this.comparisonStart = comparisonStart;
    }

    public void canonical(CanonicalPriceEvent event, IntrabarMarketState state) {
        if (latest != null && event.timeMsc() < latest.timeMsc()) fail(Difference.ORDER_ERROR);
        if (event.timeMsc() <= watermark) fail(Difference.SOURCE_GAP, "late timeMsc=" + event.timeMsc() + " watermark=" + watermark); // late data invalidates a previously released join
        if (latest == null || latest.candle().bucketStartTimeMsc() != state.candle().bucketStartTimeMsc()) bucketsObserved++;
        if (latest != null && state.completedCandle() != null && !finalized.contains(latest.candle().bucketStartTimeMsc()))
            finalCanonical.put(latest.candle().bucketStartTimeMsc(), latest);
        var witnesses = extremes.computeIfAbsent(state.candle().bucketStartTimeMsc(), key -> new CanonicalPriceEvent[2]);
        if (witnesses[0] == null || event.price() > witnesses[0].price()) witnesses[0] = event;
        if (witnesses[1] == null || event.price() < witnesses[1].price()) witnesses[1] = event;
        latest = state;
        canonicalEvents++;
        Group previous = states.get(event.timeMsc());
        states.put(event.timeMsc(), new Group(state, previous == null ? 1 : previous.count() + 1));
        context.addLast(event);
        if (context.size() > CONTEXT) context.removeFirst();
        if (states.size() > MAX_STATES || finalCanonical.size() > 300) fail(Difference.SOURCE_GAP);
    }

    public void old(ShadowObservation observation) {
        if (observation.timeMsc() < oldTime) fail(Difference.ORDER_ERROR);
        oldTime = observation.timeMsc();
        // The previous last observation of a bucket is our measured OLD close.
        // REST corrections after the rollover must not overwrite that measurement.
        if (observation.candle() != null && !finalized.contains(observation.candle().bucketStartTimeMsc())
                && observation.timeMsc() >= comparisonStart && observation.freshAfterSync()
                && observation.sma9() != null && observation.sma21() != null
                && M5Bucket.start(observation.timeMsc()) == observation.candle().bucketStartTimeMsc())
            finalOld.put(observation.candle().bucketStartTimeMsc(), observation);
        pending.add(observation);
        if (pending.size() > MAX_STATES || finalOld.size() > 300) fail(Difference.SOURCE_GAP);
        drain();
    }

    public void watermark(long through) {
        if (through < watermark) fail(Difference.ORDER_ERROR);
        watermark = through;
        drain();
    }

    private void drain() {
        while (!pending.isEmpty() && pending.peekFirst().timeMsc() <= watermark) {
            var old = pending.removeFirst();
            if (!initialized || old.timeMsc() < comparisonStart || !old.freshAfterSync()) { skipped++; continue; }
            var entry = states.floorEntry(old.timeMsc());
            if (entry == null) { count(intrabar, Set.of(Difference.MISSING_CANONICAL_STATE)); skipped++; continue; }
            var state = entry.getValue().state();
            if (old.candle() == null || old.sma9() == null || old.sma21() == null
                    || state.sma9() == null || state.sma21() == null) {
                readiness = Readiness.SHADOW_NOT_READY;
                count(intrabar, Set.of(Difference.MISSING_OLD_STATE)); skipped++; continue;
            }
            if (!old.symbol().equals(state.symbol())) fail(Difference.SOURCE_GAP);
            if (entry.getKey() == old.timeMsc() && entry.getValue().count() > 1) { ambiguous++; continue; }
            // Source has drained through OLD time, so the floor state is stable.
            readiness = Readiness.SHADOW_COMPARABLE;
            var diff = compare(old, state);
            count(intrabar, diff);
            comparisons++;
            if (firstCompared < 0) firstCompared = old.timeMsc();
            lastCompared = old.timeMsc();
            remember("INTRABAR", old, state, null, diff);
        }
        // Keep two minutes for late HTTP delivery plus its as-of predecessor.
        long limit = watermark - 120_000;
        if (!pending.isEmpty()) limit = Math.min(limit, pending.peekFirst().timeMsc());
        var predecessor = states.lowerKey(limit);
        if (predecessor != null) states.headMap(predecessor, false).clear();
    }

    /** Called only with official closed bars; returns false until both streams have passed their end. */
    public boolean official(IntrabarMarketState official, long closedThrough) {
        long bucket = official.candle().bucketStartTimeMsc();
        if (bucket + M5Bucket.DURATION_MSC > closedThrough) throw new IllegalArgumentException("Official candle still open");
        if (finalized.contains(bucket) || bucket + M5Bucket.DURATION_MSC <= comparisonStart) return true;
        if (watermark < bucket + M5Bucket.DURATION_MSC || oldTime < bucket + M5Bucket.DURATION_MSC) return false;
        var canonical = finalCanonical.remove(bucket);
        // A quote-only following bucket can still close the previous LAST bar.
        if (canonical == null && latest != null && latest.candle().bucketStartTimeMsc() == bucket) canonical = latest;
        var old = finalOld.remove(bucket);
        if (canonical == null) {
            count(closedOfficial, Set.of(Difference.MISSING_CANONICAL_STATE));
            finalized.add(bucket);
            return true;
        }
        finalized.add(bucket);
        bucketsCompared++;
        if (bucket >= comparisonStart) completeSessionBuckets++;
        var canonOfficial = compare(asOld(canonical), official);
        if (closedComparisons.size() >= 300) fail(Difference.SOURCE_GAP);
        closedComparisons.add(new ClosedComparison(bucket, bucket >= comparisonStart, old, canonical, official,
                old == null ? Set.of(Difference.MISSING_OLD_STATE) : Set.copyOf(compare(old, canonical)),
                Set.copyOf(canonOfficial),
                old == null ? Set.of(Difference.MISSING_OLD_STATE) : Set.copyOf(compare(old, official))));
        count(closedOfficial, canonOfficial);
        if (old == null) count(closedOld, Set.of(Difference.MISSING_OLD_STATE));
        else {
            fullThreeWayComparisons++;
            var oldCanon = compare(old, canonical);
            count(closedOld, oldCanon);
            count(oldOfficial, compare(old, official));
            remember("CLOSED_OLD_CANONICAL", old, canonical, official, oldCanon);
        }
        remember("CLOSED_CANONICAL_OFFICIAL", old, canonical, official, canonOfficial);
        extremes.remove(bucket);
        return true;
    }

    public static Set<Difference> compare(ShadowObservation old, IntrabarMarketState state) {
        var result = EnumSet.noneOf(Difference.class);
        if (old.price() != state.price()) result.add(Difference.PRICE_MISMATCH);
        if (old.candle().bucketStartTimeMsc() != state.candle().bucketStartTimeMsc()) result.add(Difference.BUCKET_MISMATCH);
        if (old.candle().open() != state.candle().open()) result.add(Difference.OPEN_MISMATCH);
        if (old.candle().high() != state.candle().high()) result.add(Difference.HIGH_MISMATCH);
        if (old.candle().low() != state.candle().low()) result.add(Difference.LOW_MISMATCH);
        if (old.candle().close() != state.candle().close()) result.add(Difference.CLOSE_MISMATCH);
        if (!near(old.sma9(), state.sma9())) result.add(Difference.SMA9_MISMATCH);
        if (!near(old.sma21(), state.sma21())) result.add(Difference.SMA21_MISMATCH);
        return result;
    }
    private static boolean near(Double a, Double b) {
        return a != null && b != null && Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= SMA_TOLERANCE;
    }
    public static ShadowObservation asOld(IntrabarMarketState s) {
        return new ShadowObservation(s.symbol(), s.timeMsc(), s.price(), s.candle(), s.sma9(), s.sma21(), true, 0);
    }
    private void remember(String phase, ShadowObservation old, IntrabarMarketState state,
                          IntrabarMarketState official, Set<Difference> diff) {
        if (diff.isEmpty() || evidence.size() >= MAX_EVIDENCE) return;
        // Aggregate repeated field sets in the same phase/bucket; count every sample above.
        if (evidence.stream().anyMatch(e -> e.phase().equals(phase) && e.bucket() == state.candle().bucketStartTimeMsc()
                && e.differences().equals(diff))) return;
        List<CanonicalPriceEvent> around = new ArrayList<>(context);
        var witness = extremes.get(state.candle().bucketStartTimeMsc());
        if (witness != null) for (var e : witness) if (e != null && !around.contains(e)) around.add(e);
        evidence.add(new Evidence(phase, state.candle().bucketStartTimeMsc(), state.timeMsc(), Set.copyOf(diff),
                old, state, official, List.copyOf(around)));
    }
    private static void count(Map<Difference, Long> map, Set<Difference> differences) {
        differences.forEach(d -> map.merge(d, 1L, Long::sum));
    }
    public void fail(Difference difference) { fail(difference, ""); }
    private void fail(Difference difference, String detail) {
        count(intrabar, Set.of(difference));
        throw new IllegalStateException(difference.name() + (detail.isEmpty() ? "" : " " + detail));
    }
    public int evidenceCount() { return evidence.size(); }
    public Evidence evidence(int index) { return evidence.get(index); }
    public Readiness readiness() { return readiness; }
    public long eventComparisons() { return comparisons; }
    public long firstCompared() { return firstCompared; }
    public long lastCompared() { return lastCompared; }
    public long bucketsCompared() { return bucketsCompared; }
    public long fullThreeWayComparisons() { return fullThreeWayComparisons; }
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("readiness", readiness);
        report.put("canonicalEventsIncludingBootstrap", canonicalEvents);
        report.put("m5BucketsObserved", bucketsObserved);
        report.put("m5BucketsCompared", bucketsCompared);
        report.put("completeSessionBucketsCompared", completeSessionBuckets);
        report.put("fullThreeWayComparisons", fullThreeWayComparisons);
        report.put("eventComparisons", comparisons);
        report.put("notReadySamples", skipped);
        report.put("ambiguousSameMillisecond", ambiguous);
        report.put("pendingOldStates", pending.size());
        report.put("unverifiedClosedCanonicalBuckets", finalCanonical.size());
        report.put("oldVsCanonicalIntrabar", intrabar);
        report.put("oldVsCanonicalClosed", closedOld);
        report.put("canonicalVsOfficialClosed", closedOfficial);
        report.put("oldVsOfficialClosed", oldOfficial);
        report.put("closedComparisons", List.copyOf(closedComparisons));
        report.put("importantDivergences", List.copyOf(evidence));
        return report;
    }
    public boolean officialMatches() { return closedOfficial.isEmpty(); }
    public long completeSessionBuckets() { return completeSessionBuckets; }
    public boolean allClosedVerified() { return finalCanonical.isEmpty(); }
}
