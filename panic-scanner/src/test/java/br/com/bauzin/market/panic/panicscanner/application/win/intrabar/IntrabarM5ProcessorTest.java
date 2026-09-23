package br.com.bauzin.market.panic.panicscanner.application.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntrabarM5ProcessorTest {
    private static final String SYMBOL = "WINV26";
    private static final long START = 30_000_000;
    private static final long M5 = M5Bucket.DURATION_MSC;

    private CanonicalPriceEvent event(long time, double price) { return new CanonicalPriceEvent(SYMBOL, time, price); }
    private IntrabarCandleSnapshot bar(long time, double close) {
        return new IntrabarCandleSnapshot(SYMBOL, time, close, close, close, close);
    }
    private List<IntrabarCandleSnapshot> history(int count, double price) {
        return IntStream.range(0, count).mapToObj(i -> bar(START - (count - i) * M5, price)).toList();
    }
    private IntrabarM5Processor warmed() {
        var p = new IntrabarM5Processor();
        p.warmUp(SYMBOL, START, history(20, 188700));
        return p;
    }

    @Test void basicOhlcSameBucketAndSnapshotsAreImmutable() {
        var p = new IntrabarM5Processor();
        var first = p.onEvent(event(START, 188700));
        p.onEvent(event(START + 1, 188710));
        p.onEvent(event(START + 2, 188690));
        var last = p.onEvent(event(START + 3, 188705));
        assertEquals(new IntrabarCandleSnapshot(SYMBOL, START, 188700, 188710, 188690, 188705), last.candle());
        assertEquals(bar(START, 188700), first.candle());
        assertNull(last.completedCandle());
        assertEquals(1, p.retainedBarCount());
    }

    @Test void automaticRolloverAtMillisecondBoundary() {
        var p = new IntrabarM5Processor();
        var previous = p.onEvent(event(START + M5 - 100, 188700));
        var next = p.onEvent(event(START + M5 + 10, 188710));
        assertEquals(previous.candle(), next.completedCandle());
        assertEquals(bar(START + M5, 188710), next.candle());
        assertEquals(2, p.retainedBarCount());
    }

    @Test void gapsDoNotCreateArtificialBarsOrPrices() {
        var p = warmed();
        var first = p.onEvent(event(START + 4 * 60_000, 188700));
        var next = p.onEvent(event(START + 17 * 60_000, 188710));
        assertEquals(first.candle(), next.completedCandle());
        assertEquals(START + 3 * M5, next.candle().bucketStartTimeMsc());
        assertEquals(22, p.retainedBarCount());
        assertEquals(188700 + 10.0 / 21, next.sma21(), 1e-9);
    }

    @Test void sameMillisecondAndIdenticalEventsAreAllAcceptedInArrivalOrder() {
        var p = new IntrabarM5Processor();
        p.onEvent(event(1000, 188700));
        p.onEvent(event(1000, 188705));
        var last = p.onEvent(event(1000, 188710));
        assertEquals(188710, last.candle().close());
        assertEquals(last, p.onEvent(event(1000, 188710)));
        assertEquals(1000, p.previousTimeMsc());
    }

    @Test void outOfOrderIsRejectedBeforeAnyMutationIncludingIndicators() {
        var p = warmed();
        var control = warmed();
        var expected = p.onEvent(event(START + 2000, 188705));
        control.onEvent(event(START + 2000, 188705));
        assertThrows(IllegalArgumentException.class, () -> p.onEvent(event(START + 1999, 999999)));
        assertEquals(expected.candle(), p.currentCandle());
        assertEquals(START + 2000, p.previousTimeMsc());
        assertEquals(control.onEvent(event(START + M5, 188710)), p.onEvent(event(START + M5, 188710)));
    }

    @Test void invalidEventsAndCandlesFailAtConstruction() {
        for (double price : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> event(START, price));
        assertThrows(IllegalArgumentException.class, () -> event(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> event(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalPriceEvent(" ", 0, 1));
        assertThrows(NullPointerException.class, () -> new CanonicalPriceEvent(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> bar(1, 100));
        assertThrows(IllegalArgumentException.class, () -> new IntrabarCandleSnapshot(SYMBOL, 0, 100, 90, 80, 100));
        var p = new IntrabarM5Processor();
        assertThrows(NullPointerException.class, () -> p.onEvent(null));
        assertNull(p.symbol());
        assertEquals(0, p.retainedBarCount());
    }

    @Test void mixedSymbolsRejectedBeforeMutationAndNormalizationIsStable() {
        var p = warmed();
        var before = p.onEvent(new CanonicalPriceEvent(" winv26 ", START, 100));
        assertThrows(IllegalArgumentException.class, () -> p.onEvent(new CanonicalPriceEvent("PETR4", START + 1, 1)));
        assertEquals(SYMBOL, p.symbol());
        assertEquals(before.candle(), p.currentCandle());
        assertEquals(START, p.previousTimeMsc());
    }

    @Test void resetClearsWarmupSymbolCandleTimestampAndStart() {
        var p = warmed();
        p.onEvent(event(START, 1));
        p.reset();
        assertNull(p.symbol());
        assertNull(p.currentCandle());
        assertEquals(-1, p.previousTimeMsc());
        assertEquals(0, p.retainedBarCount());
        var state = p.onEvent(new CanonicalPriceEvent("PETR4", 0, 10));
        assertNull(state.sma9());
        assertNull(state.sma21());
    }

    @Test void warmupIsClosedBeforeAnalysisAndInvalidInputDoesNotPartiallySeed() {
        var p = new IntrabarM5Processor();
        for (var invalid : List.of(List.of(bar(START - M5, 1), bar(START, 1)),
                List.of(bar(START - M5, 1), bar(START - M5, 2)),
                List.of(bar(START - M5, 1), bar(START - 2 * M5, 2)),
                List.of(new IntrabarCandleSnapshot("PETR4", START - M5, 1, 1, 1, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> p.warmUp(SYMBOL, START, invalid));
            assertNull(p.symbol());
            assertEquals(0, p.retainedBarCount());
        }
        p.warmUp(SYMBOL, START + 123, history(20, 100));
        assertNull(p.currentCandle());
        assertEquals(-1, p.previousTimeMsc());
        assertThrows(IllegalArgumentException.class, () -> p.onEvent(event(START + 122, 1)));
        assertEquals(100, p.onEvent(event(START + 123, 100)).sma21());
        assertThrows(IllegalStateException.class, () -> p.warmUp(SYMBOL, START, List.of()));
    }

    @Test void smaFullWindowsKnownValuesAndStructureReadiness() {
        var p = new IntrabarM5Processor();
        for (int i = 1; i <= 22; i++) {
            var state = p.onEvent(event(i * M5, i));
            if (i < 9) assertNull(state.sma9()); else assertEquals(i - 4.0, state.sma9());
            if (i < 21) {
                assertNull(state.sma21());
                assertTrue(state.structureSample().isEmpty());
            } else {
                assertEquals(i - 10.0, state.sma21());
                assertEquals(new MarketStructureSample(SYMBOL, i * M5, i, i - 4.0, i - 10.0), state.structureSample().orElseThrow());
            }
        }
    }

    @Test void smaIntrabarFractionalValuesAndCacheInvalidation() {
        var p = warmed();
        var first = p.onEvent(event(START, 188700));
        assertEquals(188700, first.sma9());
        for (int delta : new int[]{10, 20, 50, 0}) {
            var state = p.onEvent(event(START, 188700 + delta));
            assertEquals(188700 + delta / 9.0, state.sma9(), 1e-9);
            assertEquals(188700 + delta / 21.0, state.sma21(), 1e-9);
        }
        var fractional = new IntrabarM5Processor();
        var history = new ArrayList<>(history(20, 188700));
        history.set(0, bar(START - 20 * M5, 188750));
        fractional.warmUp(SYMBOL, START, history);
        var state = fractional.onEvent(event(START, 188700));
        assertEquals(188700, state.price());
        assertEquals(188702.38095238095, state.sma21(), 1e-9);
        assertNotEquals(Math.rint(state.sma21()), state.sma21());
    }

    @Test void parityWithExistingTa4jAdapterForSameOfficialBaseline() {
        var history = history(20, 188700);
        var official = new ArrayList<Mt5Candle>();
        history.forEach(c -> official.add(new Mt5Candle(c.bucketStartTimeMsc() / 1000, c.open(), c.high(), c.low(), c.close(), 9, 100)));
        official.add(new Mt5Candle(START / 1000, 188700, 188750, 188650, 188700, 9, 100));
        var old = new Ta4jMt5Sma9Adapter();
        old.synchronize(official);
        var p = warmed();
        p.seedCurrentCandle(Mt5CanonicalPriceMapper.candle(SYMBOL, official.getLast()));
        for (int i = 0; i < 100; i++) {
            double price = 188700 + (i % 7 - 3) * 5;
            var state = p.onEvent(event(START + i, price));
            var averages = old.onTick(new Mt5Tick(START / 1000, START + i, 0, 0, price));
            assertEquals(old.candleSnapshot(SYMBOL), state.candle());
            assertEquals(averages.value(), state.sma9());
            assertEquals(averages.sma21(), state.sma21());
        }
    }

    @Test void independentSyntheticLiveAndReplaySourcesProduceIdenticalNonEmptyInteractions() {
        List<CanonicalPriceEvent> events = new ArrayList<>();
        int[] offsets = {100, 50, 35, 20, 8, 2, -5, 4, 15, 25, 50, 100};
        for (int i = 0; i < offsets.length; i++) events.add(event(START + i * 1000L, 188700 + offsets[i]));
        events.add(events.getLast()); // duplicate is delivered, not discarded
        events.add(event(START + M5, 188720));
        events.add(event(START + 3 * M5, 188710));
        var live = new SyntheticLiveSource();
        var replay = new SyntheticReplaySource(events);
        var a = warmed(); var b = warmed();
        var engineA = new MarketStructureEngine(); var engineB = new MarketStructureEngine();
        List<IntrabarMarketState> statesA = new ArrayList<>(), statesB = new ArrayList<>();
        List<MarketStructureUpdate> updatesA = new ArrayList<>(), updatesB = new ArrayList<>();
        live.subscribe(e -> { var state = a.onEvent(e); statesA.add(state); updatesA.add(engineA.onSample(state.structureSample().orElseThrow())); });
        events.forEach(live::publish);
        replay.stream(e -> { var state = b.onEvent(e); statesB.add(state); updatesB.add(engineB.onSample(state.structureSample().orElseThrow())); });
        assertEquals(events.size(), statesA.size());
        assertEquals(statesA, statesB); // all OHLC, time, SMA9 and SMA21, including rollover
        assertEquals(statesA.stream().map(IntrabarMarketState::structureSample).toList(),
                statesB.stream().map(IntrabarMarketState::structureSample).toList());
        assertEquals(updatesA, updatesB); // all per-step snapshots, events and completed interactions
        assertTrue(updatesA.stream().anyMatch(u -> !u.completedInteractions().isEmpty()));
    }

    @Test void retainedMemoryAndAveragesRemainCorrectAfterEviction() {
        var p = new IntrabarM5Processor();
        for (int i = 1; i <= 2500; i++) {
            var state = p.onEvent(event(i * M5, i));
            if (i >= 21) {
                assertEquals(i - 4.0, state.sma9());
                assertEquals(i - 10.0, state.sma21());
                var changed = p.onEvent(event(i * M5, i + 21));
                assertEquals(i - 9.0, changed.sma21());
                p.onEvent(event(i * M5, i));
            }
        }
        assertEquals(IntrabarM5Processor.MAX_BARS, p.retainedBarCount());
    }

    @Test void seedRestrictionsAndRejectedSeedLeaveStateUntouched() {
        var p = new IntrabarM5Processor();
        assertThrows(IllegalStateException.class, () -> p.seedCurrentCandle(bar(START, 1)));
        p.warmUp(SYMBOL, START, List.of());
        assertThrows(IllegalArgumentException.class, () -> p.seedCurrentCandle(bar(START + M5, 1)));
        assertNull(p.currentCandle());
        p.seedCurrentCandle(bar(START, 1));
        assertThrows(IllegalStateException.class, () -> p.seedCurrentCandle(bar(START, 2)));
        p.onEvent(event(START, 3));
        assertThrows(IllegalStateException.class, () -> p.seedCurrentCandle(bar(START, 4)));
    }

    @Test void rawBucketsHaveNoTimezoneAdjustment() {
        assertEquals(0, M5Bucket.start(0));
        assertEquals(0, M5Bucket.start(299999));
        assertEquals(300000, M5Bucket.start(300000));
        assertEquals(1790100000000L, M5Bucket.start(1790100126055L));
    }

    private static final class SyntheticLiveSource {
        private Consumer<CanonicalPriceEvent> listener;
        void subscribe(Consumer<CanonicalPriceEvent> listener) { this.listener = listener; }
        void publish(CanonicalPriceEvent event) { listener.accept(event); }
    }
    private record SyntheticReplaySource(List<CanonicalPriceEvent> events) {
        void stream(Consumer<CanonicalPriceEvent> consumer) { events.forEach(consumer); }
    }
}
