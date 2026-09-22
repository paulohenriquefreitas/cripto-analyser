package br.com.bauzin.market.panic.panicscanner.application.win.structure;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.*;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Constant-memory state machine for one price reference. */
final class MovingAverageTracker {
    private final PriceReference reference;
    private final MarketStructureConfig config;
    private MovingAverageState state = MovingAverageState.FAR;
    private Point previous;
    private Point approachStart;
    private int decreasingTransitions;
    private ActiveInteraction active;
    private MovingAverageInteraction lastCompleted;

    MovingAverageTracker(PriceReference reference, MarketStructureConfig config) {
        this.reference = reference;
        this.config = config;
    }

    MovingAverageSnapshot update(MarketStructureSample sample,
                                 List<MarketStructureEvent> events,
                                 List<MovingAverageInteraction> completed) {
        Point current = new Point(sample.timeMsc(), sample.price(), sample.average(reference));
        if (active == null) observeApproach(sample.symbol(), current, events);
        if (active == null && current.absoluteDistance() <= config.nearDistancePoints()) {
            enterNear(sample.symbol(), current, events);
        }
        if (active != null) observeInteraction(sample.symbol(), current, events, completed);
        previous = current;
        return snapshot(current);
    }

    private void observeApproach(String symbol, Point current, List<MarketStructureEvent> events) {
        if (previous == null || current.absoluteDistance() >= previous.absoluteDistance()) {
            decreasingTransitions = 0;
            approachStart = null;
            state = MovingAverageState.FAR;
            return;
        }
        if (decreasingTransitions == 0) approachStart = previous;
        decreasingTransitions++;
        if (state != MovingAverageState.APPROACHING
                && decreasingTransitions >= config.approachObservationCount()
                && approachStart.absoluteDistance() - current.absoluteDistance()
                >= config.approachMinimumReductionPoints()) {
            state = MovingAverageState.APPROACHING;
            events.add(event(MarketStructureEventType.APPROACH_STARTED, current,
                    PriceSide.fromDistance(approachStart.distance())));
        }
    }

    private void enterNear(String symbol, Point current, List<MarketStructureEvent> events) {
        Point start = approachStart != null ? approachStart : previous != null ? previous : current;
        PriceSide approachSide = PriceSide.fromDistance(start.distance());
        if (approachSide == PriceSide.AT && previous != null) {
            approachSide = PriceSide.fromDistance(previous.distance());
        }
        active = new ActiveInteraction(symbol, approachSide, start, current);
        state = MovingAverageState.NEAR;
        events.add(event(MarketStructureEventType.NEAR_ENTERED, current, approachSide));
    }

    private void observeInteraction(String symbol, Point current,
                                    List<MarketStructureEvent> events,
                                    List<MovingAverageInteraction> completed) {
        active.observe(current);
        if (!active.touched && current.absoluteDistance() <= config.touchTolerancePoints()) {
            active.touched = true;
            active.touch = current;
            state = MovingAverageState.TOUCHING;
            events.add(event(MarketStructureEventType.TOUCH_DETECTED, current,
                    PriceSide.fromDistance(current.distance())));
        }
        if (!active.crossed && crossed(active.approachSide, current.distance())) {
            active.crossed = true;
            active.cross = current;
            state = MovingAverageState.PENETRATED;
            events.add(event(MarketStructureEventType.CROSS_DETECTED, current,
                    PriceSide.fromDistance(current.distance())));
        }
        active.maxPenetration = Math.max(active.maxPenetration,
                penetration(active.approachSide, current.distance()));
        if (!active.movingAway
                && current.absoluteDistance() - active.minimumAbsoluteDistance
                >= config.approachMinimumReductionPoints()) {
            active.movingAway = true;
            active.movingAwayTimeMsc = current.timeMsc();
            state = MovingAverageState.MOVING_AWAY;
            events.add(event(MarketStructureEventType.MOVING_AWAY_DETECTED, current,
                    PriceSide.fromDistance(current.distance())));
        }
        if (current.absoluteDistance() >= config.exitDistancePoints()) {
            MovingAverageInteraction interaction = active.complete(reference, current);
            lastCompleted = interaction;
            completed.add(interaction);
            events.add(event(MarketStructureEventType.INTERACTION_COMPLETED, current,
                    PriceSide.fromDistance(current.distance())));
            active = null;
            approachStart = null;
            decreasingTransitions = 0;
            state = MovingAverageState.FAR;
        }
    }

    private static boolean crossed(PriceSide approachSide, double distance) {
        return approachSide == PriceSide.ABOVE && distance < 0
                || approachSide == PriceSide.BELOW && distance > 0;
    }

    private static double penetration(PriceSide approachSide, double distance) {
        if (approachSide == PriceSide.ABOVE) return Math.max(0, -distance);
        if (approachSide == PriceSide.BELOW) return Math.max(0, distance);
        return 0;
    }

    private MarketStructureEvent event(MarketStructureEventType type, Point point, PriceSide side) {
        return new MarketStructureEvent(type, reference, state, point.timeMsc(), point.price(),
                point.average(), point.distance(), side);
    }

    private MovingAverageSnapshot snapshot(Point point) {
        return new MovingAverageSnapshot(reference, state, point.timeMsc(), point.price(),
                point.average(), point.distance(), point.absoluteDistance(), active != null);
    }

    MovingAverageSnapshot snapshot() {
        return previous == null ? null : snapshot(previous);
    }

    MovingAverageInteraction lastCompleted() {
        return lastCompleted;
    }

    void reset() {
        state = MovingAverageState.FAR;
        previous = null;
        approachStart = null;
        decreasingTransitions = 0;
        active = null;
        lastCompleted = null;
    }

    private record Point(long timeMsc, double price, double average) {
        double distance() { return price - average; }
        double absoluteDistance() { return Math.abs(distance()); }
    }

    private static final class ActiveInteraction {
        private final String symbol;
        private final PriceSide approachSide;
        private final Point start;
        private final Point near;
        private Point touch;
        private Point cross;
        private Point closest;
        private double minimumAbsoluteDistance;
        private double maxPenetration;
        private boolean touched;
        private boolean crossed;
        private boolean movingAway;
        private long movingAwayTimeMsc;

        private ActiveInteraction(String symbol, PriceSide approachSide, Point start, Point near) {
            this.symbol = symbol;
            this.approachSide = approachSide;
            this.start = start;
            this.near = near;
            closest = near;
            minimumAbsoluteDistance = near.absoluteDistance();
        }

        private void observe(Point point) {
            if (point.absoluteDistance() < minimumAbsoluteDistance) {
                minimumAbsoluteDistance = point.absoluteDistance();
                closest = point;
            }
        }

        private MovingAverageInteraction complete(PriceReference reference, Point exit) {
            return new MovingAverageInteraction(symbol, reference, approachSide,
                    start.timeMsc(), near.timeMsc(),
                    touch == null ? OptionalLong.empty() : OptionalLong.of(touch.timeMsc()),
                    cross == null ? OptionalLong.empty() : OptionalLong.of(cross.timeMsc()),
                    movingAwayTimeMsc, exit.timeMsc(), start.price(), start.distance(), near.average(),
                    touch == null ? OptionalDouble.empty() : OptionalDouble.of(touch.average()),
                    exit.average(), minimumAbsoluteDistance, closest.price(), closest.timeMsc(),
                    touched, crossed, maxPenetration, PriceSide.fromDistance(exit.distance()),
                    exit.price(), exit.distance(), exit.timeMsc() - start.timeMsc(),
                    exit.timeMsc() - near.timeMsc());
        }
    }
}
