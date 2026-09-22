package br.com.bauzin.market.panic.panicscanner.application.win.structure;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketStructureEngineTest {
    private static final double SMA = 188_700;

    @Test void farPriceRemainsFar() {
        assertEquals(MovingAverageState.FAR, engine().onSample(sample(0, 50)).sma21().state());
    }

    @Test void detectsApproachingFromAboveAfterConfiguredObservations() {
        var updates = feed(engine(), 50, 40, 30, 20);
        assertEquals(MovingAverageState.APPROACHING, updates.getLast().sma21().state());
        assertEvent(updates, PriceReference.SMA21, MarketStructureEventType.APPROACH_STARTED);
    }

    @Test void detectsApproachingFromBelow() {
        var updates = feed(engine(), -50, -40, -30, -20);
        assertEquals(MovingAverageState.APPROACHING, updates.getLast().sma21().state());
    }

    @Test void entersNearRegionAndPreservesApproachSide() {
        var updates = feed(engine(), 50, 40, 30, 20, 8);
        MarketStructureEvent near = event(updates, PriceReference.SMA21, MarketStructureEventType.NEAR_ENTERED);
        assertEquals(PriceSide.ABOVE, near.side());
        assertEquals(MovingAverageState.NEAR, updates.getLast().sma21().state());
    }

    @Test void touchUsesToleranceWithoutExactEquality() {
        var updates = feed(engine(), 30, 20, 8, 2);
        assertEvent(updates, PriceReference.SMA21, MarketStructureEventType.TOUCH_DETECTED);
        assertEquals(MovingAverageState.TOUCHING, updates.getLast().sma21().state());
    }

    @Test void penetrationFromAboveIsDetected() {
        var updates = feed(engine(), 30, 20, 8, 2, -3);
        assertEvent(updates, PriceReference.SMA21, MarketStructureEventType.CROSS_DETECTED);
        assertEquals(MovingAverageState.PENETRATED, updates.getLast().sma21().state());
    }

    @Test void penetrationFromBelowIsDetected() {
        var updates = feed(engine(), -30, -20, -8, -2, 3);
        assertEvent(updates, PriceReference.SMA21, MarketStructureEventType.CROSS_DETECTED);
    }

    @Test void maximumPenetrationIsMeasuredAgainstEachCurrentAverage() {
        MovingAverageInteraction value = complete(50, 35, 20, 8, 2, -1, -5, -3, 25);
        assertEquals(5, value.maxPenetrationPoints());
    }

    @Test void detectsMovingAwayTowardApproachSide() {
        var updates = feed(engine(), 30, 20, 8, 2, -3, 4, 12);
        MarketStructureEvent event = event(updates, PriceReference.SMA21,
                MarketStructureEventType.MOVING_AWAY_DETECTED);
        assertEquals(PriceSide.ABOVE, event.side());
    }

    @Test void detectsMovingAwayTowardOppositeSide() {
        var updates = feed(engine(), 30, 20, 8, 2, -3, -12);
        assertEquals(PriceSide.BELOW, event(updates, PriceReference.SMA21,
                MarketStructureEventType.MOVING_AWAY_DETECTED).side());
    }

    @Test void completesNearApproachWithoutTouchOrCross() {
        MovingAverageInteraction value = complete(50, 35, 20, 8, 12, 21);
        assertFalse(value.touched());
        assertFalse(value.crossed());
    }

    @Test void hysteresisKeepsInteractionActiveBetweenNearAndExitThresholds() {
        var updates = feed(engine(), 30, 20, 8, 11, 9, 11, 19.9);
        assertTrue(updates.getLast().sma21().interactionActive());
        assertTrue(updates.stream().flatMap(u -> u.completedInteractions().stream()).findAny().isEmpty());
    }

    @Test void movingAverageChangesAreUsedPerSample() {
        MarketStructureEngine engine = engine();
        double[][] values = {
                {188750, 188700}, {188730, 188701}, {188715, 188703},
                {188706, 188704}, {188701, 188705}, {188710, 188706},
                {188725, 188708}, {188730, 188709}
        };
        MovingAverageInteraction completed = null;
        for (int i = 0; i < values.length; i++) {
            var update = engine.onSample(new MarketStructureSample(
                    "WINV26", i, values[i][0], 190_000, values[i][1]));
            if (!update.completedInteractions().isEmpty()) completed = update.completedInteractions().getFirst();
        }
        assertNotNull(completed);
        assertEquals(2, completed.minimumAbsoluteDistance());
        assertEquals(4, completed.maxPenetrationPoints());
        assertEquals(188704, completed.movingAverageAtNear());
        assertEquals(188704, completed.movingAverageAtTouch().orElseThrow());
        assertEquals(188709, completed.movingAverageAtExit());
        assertEquals(21, completed.exitDistance());
    }

    @Test void sma9AndSma21TrackIndependentlyAtTheSameTime() {
        MarketStructureEngine engine = engine();
        MarketStructureUpdate update = null;
        for (int i = 0; i < 5; i++) {
            double price = 188750 - i * 10;
            update = engine.onSample(new MarketStructureSample("WINV26", i, price,
                    188700, 188690));
        }
        assertNotNull(update);
        assertTrue(update.sma9().absoluteDistance() != update.sma21().absoluteDistance());
        assertEquals(PriceReference.SMA9, update.sma9().reference());
        assertEquals(PriceReference.SMA21, update.sma21().reference());
    }

    @Test void acceptsMultipleSamplesAtSameMillisecond() {
        MarketStructureEngine engine = engine();
        engine.onSample(sample(1, 50));
        assertDoesNotThrow(() -> engine.onSample(sample(1, 40)));
        assertEquals(40, engine.snapshot(PriceReference.SMA21).orElseThrow().distance());
    }

    @Test void rejectsOutOfOrderSampleWithoutChangingState() {
        MarketStructureEngine engine = engine();
        engine.onSample(sample(2, 50));
        assertThrows(IllegalArgumentException.class, () -> engine.onSample(sample(1, 40)));
        assertEquals(2, engine.snapshot(PriceReference.SMA21).orElseThrow().timeMsc());
    }

    @Test void rejectsInvalidSamplesAndConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarketStructureSample("WINV26", 0, Double.NaN, SMA, SMA));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketStructureSample("WINV26", 0, SMA, 0, SMA));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketStructureConfig(20, 10, 2, 3, 5));
    }

    @Test void resetClearsSnapshotsInteractionsTimeAndSymbol() {
        MarketStructureEngine engine = engine();
        complete(engine, 50, 20, 8, 2, 25);
        engine.reset();
        assertTrue(engine.snapshot(PriceReference.SMA21).isEmpty());
        assertTrue(engine.lastCompleted(PriceReference.SMA21).isEmpty());
        assertDoesNotThrow(() -> engine.onSample(
                new MarketStructureSample("WINZ26", 0, SMA + 50, SMA, SMA)));
    }

    @Test void completedInteractionContainsAllObjectiveMilestones() {
        MovingAverageInteraction value = complete(50, 35, 20, 8, 2, -2, -5, 4, 15, 25);
        assertEquals(PriceReference.SMA21, value.reference());
        assertEquals(PriceSide.ABOVE, value.approachSide());
        assertEquals(PriceSide.ABOVE, value.exitSide());
        assertTrue(value.touched());
        assertTrue(value.crossed());
        assertEquals(2, value.minimumAbsoluteDistance());
        assertEquals(5, value.maxPenetrationPoints());
        assertEquals(25, value.exitDistance());
        assertEquals(value.endTimeMsc() - value.startTimeMsc(), value.durationMsc());
        assertEquals(value.endTimeMsc() - value.nearTimeMsc(), value.timeNearAverageMsc());
        assertTrue(value.touchTimeMsc().isPresent());
        assertTrue(value.crossTimeMsc().isPresent());
    }

    @Test void domainEventsContainNoTradingDecision() {
        var eventNames = List.of(MarketStructureEventType.values()).stream().map(Enum::name).toList();
        assertFalse(eventNames.contains("BUY"));
        assertFalse(eventNames.contains("SELL"));
        assertFalse(eventNames.contains("ALERT"));
        assertFalse(eventNames.contains("REJECTION"));
    }

    @Test void mixedSymbolsRequireExplicitReset() {
        MarketStructureEngine engine = engine();
        engine.onSample(sample(0, 50));
        assertThrows(IllegalArgumentException.class, () -> engine.onSample(
                new MarketStructureSample("WINZ26", 1, SMA + 40, SMA, SMA)));
    }

    private static MarketStructureEngine engine() {
        return new MarketStructureEngine(MarketStructureConfig.defaults());
    }

    private static MarketStructureSample sample(long time, double distance) {
        return new MarketStructureSample("WINV26", time, SMA + distance, 190_000, SMA);
    }

    private static List<MarketStructureUpdate> feed(MarketStructureEngine engine, double... distances) {
        List<MarketStructureUpdate> result = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) result.add(engine.onSample(sample(i, distances[i])));
        return result;
    }

    private static MovingAverageInteraction complete(double... distances) {
        return complete(engine(), distances);
    }

    private static MovingAverageInteraction complete(MarketStructureEngine engine, double... distances) {
        return feed(engine, distances).stream().flatMap(u -> u.completedInteractions().stream())
                .findFirst().orElseThrow();
    }

    private static void assertEvent(List<MarketStructureUpdate> updates, PriceReference reference,
                                    MarketStructureEventType type) {
        event(updates, reference, type);
    }

    private static MarketStructureEvent event(List<MarketStructureUpdate> updates,
                                              PriceReference reference,
                                              MarketStructureEventType type) {
        return updates.stream().flatMap(u -> u.events().stream())
                .filter(e -> e.reference() == reference && e.type() == type)
                .findFirst().orElseThrow();
    }
}
