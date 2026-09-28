package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class Pullback09ResearchProfileTest {
    private static final String SYMBOL = "WINV26";
    private static final long C1 = 9_000_000L, C2 = C1 + 300_000, C3 = C2 + 300_000, C4 = C3 + 300_000;

    @Test
    void researchPreservesAllSetupEventsAndUsesFirstLastOfC4() {
        Pullback09Setup baseline = new Pullback09Setup(), observed = new Pullback09Setup();
        Pullback09ResearchEngine engine = new Pullback09ResearchEngine();
        List<Pullback09Setup.Event> expected = new ArrayList<>(), actual = new ArrayList<>();
        long[] times = {C1, C1 + 1, C2, C2 + 1, C2 + 2, C3, C3 + 1, C3 + 2, C4 + 7, C4 + 8};
        double[] prices = {90, 150, 150, 100, 140, 149, 1000, 151, 120, 125};
        IntrabarCandleSnapshot previous = null;
        for (int i = 0; i < times.length; i++) {
            long bucket = M5Bucket.start(times[i]);
            boolean rollover = previous != null && previous.bucketStartTimeMsc() != bucket;
            var candle = previous == null || rollover ? bar(bucket, prices[i], prices[i], prices[i], prices[i])
                    : bar(bucket, previous.open(), Math.max(previous.high(), prices[i]), Math.min(previous.low(), prices[i]), prices[i]);
            var state = new IntrabarMarketState(SYMBOL, times[i], prices[i], candle, 100.0, 95.0, rollover ? previous : null);
            if (rollover) engine.onCandleClosed(previous);
            expected.addAll(baseline.onEvent(state));
            var events = observed.onEvent(state);
            actual.addAll(events);
            for (var event : events) {
                if (event.eventType() == Pullback09Setup.EventType.PULLB09_UP) {
                    assertThat(times[i]).isEqualTo(C4 + 7);
                    engine.onPullback09(event, observed.lastPullback09Context(), state, 110.0);
                }
            }
            engine.onPrice(new CanonicalPriceEvent(SYMBOL, times[i], prices[i]));
            previous = candle;
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).extracting(Pullback09Setup.Event::eventType).containsExactly(
                Pullback09Setup.EventType.CRZ09_UP, Pullback09Setup.EventType.RJ09_UP, Pullback09Setup.EventType.PULLB09_UP);
        var s = engine.snapshots().getFirst();
        assertThat(s.pullb09CandleTimeMsc()).isEqualTo(C3);
        assertThat(s.setupAvailableTimeMsc()).isEqualTo(C4 + 7);
        assertThat(s.referenceBucket()).isEqualTo(C4);
        assertThat(s.referencePrice()).isEqualTo(120);
        assertThat(s.candle3()).isEqualTo(bar(C3, 149, 1000, 149, 151));
        assertThat(s.rj09()).isEqualTo(bar(C2, 150, 150, 100, 140));
        assertThat(s.confirmationStrength()).isEqualTo(1);
        assertThat(s.rjMinDistanceToSma9()).isZero();
        assertThat(s.distanceToVwap()).isEqualTo(10);
        engine.onPrice(new CanonicalPriceEvent(SYMBOL, C4 + 7 + 300_000, 122));
        var outcome = engine.researchRecords().getFirst().outcome();
        assertThat(outcome.mfe5m()).isEqualTo(5); // C3 high of 1000 is excluded.
        assertThat(outcome.mae5m()).isEqualTo(-2); // Reference tick is not included in extrema.
    }

    @Test
    void allThreeWindowsUseOnlyStrictlyPosteriorLasts() {
        var tracker = new Pullback09SetupOutcomeTracker();
        tracker.register(snapshot());
        tick(tracker, -1, 9999); tick(tracker, 0, 1); tick(tracker, 0, 9999);
        tick(tracker, 1, 108); tick(tracker, 2, 96);
        tick(tracker, 300_000, 110);
        tick(tracker, 300_001, 92); tick(tracker, 900_000, 115);
        tick(tracker, 900_001, 90); tick(tracker, 1_800_000, 120);
        tick(tracker, 1_800_001, 9999);
        var o = tracker.outcomeFor("PB").orElseThrow();
        assertThat(o).isEqualTo(new Pullback09SetupOutcome("PB", true, 10.0, 4.0, true, 15.0, 8.0, true, 20.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(longs = {300_000, 900_000, 1_800_000})
    void boundariesAreInclusiveAndDoNotIncludeNextMillisecond(long horizon) {
        var tracker = new Pullback09SetupOutcomeTracker(); tracker.register(snapshot());
        tick(tracker, horizon - 1, 95);
        assertThat(complete(tracker.outcomeFor("PB").orElseThrow(), horizon)).isFalse();
        tick(tracker, horizon, 110);
        var atBoundary = tracker.outcomeFor("PB").orElseThrow();
        assertThat(complete(atBoundary, horizon)).isTrue();
        assertThat(mfe(atBoundary, horizon)).isEqualTo(10);
        tick(tracker, horizon + 1, 999);
        assertThat(mfe(tracker.outcomeFor("PB").orElseThrow(), horizon)).isEqualTo(10);
    }

    @Test
    void truncatedHistoryHasNullMetricsAndCannotChangeAfterFinish() {
        var tracker = new Pullback09SetupOutcomeTracker(); tracker.register(snapshot());
        tick(tracker, 299_999, 110); tracker.finish(); tick(tracker, 1_800_000, 900);
        assertThat(tracker.outcomeFor("PB").orElseThrow()).isEqualTo(
                new Pullback09SetupOutcome("PB", false, null, null, false, null, null, false, null, null));
        assertThat(tracker.records().getFirst().toCsvRow()).contains("INCOMPLETE");
    }

    @Test
    void duplicateRegistrationDoesNotResetExcursions() {
        var tracker = new Pullback09SetupOutcomeTracker(); tracker.register(snapshot());
        tick(tracker, 1, 130); tracker.register(snapshot()); tick(tracker, 300_000, 100);
        assertThat(tracker.records()).hasSize(1);
        assertThat(tracker.outcomeFor("PB").orElseThrow().mfe5m()).isEqualTo(30);
    }

    @Test
    void atrAndSlopesUseClosedCandlesOnlyAndSnapshotIsImmutable() {
        var engine = new Pullback09ResearchEngine();
        for (int i = 0; i < 24; i++) engine.onCandleClosed(bar(C3 - (23 - i) * 300_000L, 100, 105, 95, 100));
        var s = capture(engine, 10000);
        assertThat(s.atr14()).isEqualTo(10);
        assertThat(s.sma9Slope()).isZero();
        assertThat(s.sma21Slope()).isZero();
        assertThat(s.sma9()).isEqualTo(999.0); // C4 SMA is a separate intrabar feature.
        engine.onCandleClosed(bar(C4, 10000, 20000, 1, 15000));
        assertThat(engine.snapshots().getFirst()).isEqualTo(s);
        assertThat(s.atr14()).isEqualTo(10);
        assertThat(s.referencePrice()).isEqualTo(10000);
    }

    @Test
    void insufficientClosedHistoryLeavesAtrAndSlopesAbsent() {
        var engine = new Pullback09ResearchEngine(); engine.onCandleClosed(bar(C3, 100, 105, 95, 100));
        var s = capture(engine, 120);
        assertThat(s.atr14()).isNull(); assertThat(s.sma9Slope()).isNull(); assertThat(s.sma21Slope()).isNull();
    }

    @Test
    void closedSmaSlopesMeasureThreeBarsWithoutUsingReferencePrice() {
        var engine = new Pullback09ResearchEngine();
        for (int i = 0; i < 24; i++) engine.onCandleClosed(bar(C3 - (23 - i) * 300_000L,
                100 + i, 105 + i, 95 + i, 100 + i));
        var s = capture(engine, 10000);
        assertThat(s.sma9Slope()).isEqualTo(3);
        assertThat(s.sma21Slope()).isEqualTo(3);
    }

    @Test
    void missingTicksDoNotInventCandlesOrRejectAnAlreadyEmittedSetup() {
        var engine = new Pullback09ResearchEngine();
        var c3 = bar(C3, 109, 115, 109, 112);
        engine.onCandleClosed(c3);
        long nextObserved = C4 + 300_000;
        var state = new IntrabarMarketState(SYMBOL, nextObserved + 7, 120,
                bar(nextObserved, 120, 120, 120, 120), 100.0, 99.0, c3);
        var event = new Pullback09Setup.Event("gap", Pullback09Setup.EventType.PULLB09_UP,
                SYMBOL, nextObserved + 7, C3, 112);
        var s = engine.onPullback09(event, new Pullback09Setup.Pullback09Context(
                bar(C2, 110, 110, 99, 105), 0, 110, C2), state, 100.0);
        assertThat(s.referencePrice()).isEqualTo(120);
        assertThat(s.referenceBucket()).isEqualTo(nextObserved);
        assertThat(s.setupAvailableTimeMsc()).isEqualTo(nextObserved + 7);
    }

    @Test
    void csvExplicitlyDescribesSetupOutcomeAndExportsEveryColumn() {
        var tracker = new Pullback09SetupOutcomeTracker(); tracker.register(snapshot());
        tick(tracker, 1, 108); tick(tracker, 2, 96); tick(tracker, 300_000, 110);
        tick(tracker, 300_001, 92); tick(tracker, 900_000, 115);
        tick(tracker, 900_001, 90); tick(tracker, 1_800_000, 120);
        String csv = Pullback09ResearchRecord.toCsv(tracker.records());
        assertThat(csv).contains("referencePrice", "setupMfe5", "setupAvailableTimeMsc", "COMPLETE");
        assertThat(csv).doesNotContain("entryPrice", "BUY", "SELL");
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1].split(",", -1)).hasSameSizeAs(lines[0].split(",", -1));
    }

    private static Pullback09ResearchSnapshot capture(Pullback09ResearchEngine engine, double price) {
        var rj = bar(C2, 100, 105, 95, 99);
        var state = new IntrabarMarketState(SYMBOL, C4, price, bar(C4, price, price, price, price),
                999.0, 998.0, bar(C3, 100, 105, 95, 100));
        return engine.onPullback09(new Pullback09Setup.Event("PB", Pullback09Setup.EventType.PULLB09_UP, SYMBOL, C4, C3, 100),
                new Pullback09Setup.Pullback09Context(rj, 1, 105, C2), state, 98.0);
    }
    private static Pullback09ResearchSnapshot snapshot() {
        return new Pullback09ResearchSnapshot("PB", SYMBOL, C3, C4, 100, C4,
                bar(C2, 110, 110, 99, 105), 0.0, bar(C3, 109, 115, 109, 112), 2,
                101.0, 100.0, 1.0, 0.5, 0.2, 99.0, 1.0, 10.0);
    }
    private static IntrabarCandleSnapshot bar(long bucket, double o, double h, double l, double c) {
        return new IntrabarCandleSnapshot(SYMBOL, bucket, o, h, l, c);
    }
    private static void tick(Pullback09SetupOutcomeTracker tracker, long offset, double price) {
        tracker.onPrice(new CanonicalPriceEvent(SYMBOL, C4 + offset, price));
    }
    private static boolean complete(Pullback09SetupOutcome o, long h) {
        return h == 300_000 ? o.complete5m() : h == 900_000 ? o.complete15m() : o.complete30m();
    }
    private static Double mfe(Pullback09SetupOutcome o, long h) {
        return h == 300_000 ? o.mfe5m() : h == 900_000 ? o.mfe15m() : o.mfe30m();
    }
}
