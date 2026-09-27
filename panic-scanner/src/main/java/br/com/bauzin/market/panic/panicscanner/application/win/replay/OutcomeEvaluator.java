package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class OutcomeEvaluator {
    private final OutcomeSpecification specification;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<Long, Long> lastSequenceByTime = new LinkedHashMap<>();
    private long lastTimeMsc = -1;

    public OutcomeEvaluator(OutcomeSpecification specification) {
        this.specification = Objects.requireNonNull(specification, "specification must not be null");
    }

    public synchronized void register(RuleOccurrence occurrence) {
        registerAtSequence(occurrence, lastSequenceByTime.getOrDefault(occurrence.timeMsc(), -1L));
    }

    public synchronized void registerAtSequence(RuleOccurrence occurrence, long sequenceWatermark) {
        Objects.requireNonNull(occurrence, "occurrence must not be null");
        if (sequenceWatermark < -1) {
            throw new IllegalArgumentException("sequenceWatermark must be -1 or non-negative");
        }
        if (states.containsKey(occurrence.occurrenceId())) {
            throw new IllegalArgumentException("duplicate occurrenceId: " + occurrence.occurrenceId());
        }
        if (lastTimeMsc > occurrence.timeMsc()) {
            throw new IllegalArgumentException("occurrence is before the replay watermark");
        }
        states.put(occurrence.occurrenceId(), new State(occurrence, sequenceWatermark));
    }

    public synchronized void onPrice(PriceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (lastTimeMsc > event.timeMsc()
                || lastTimeMsc == event.timeMsc()
                && lastSequenceByTime.getOrDefault(event.timeMsc(), -1L) > event.sequence()) {
            throw new IllegalArgumentException("price events must be ordered by timeMsc and sequence");
        }
        lastTimeMsc = event.timeMsc();
        lastSequenceByTime.merge(event.timeMsc(), event.sequence(), Math::max);

        for (State state : states.values()) {
            state.accept(event, specification);
        }
    }

    public synchronized void finishReplay() {
        states.values().forEach(State::finishReplay);
    }

    public synchronized OutcomeResult result(String occurrenceId) {
        State state = states.get(Objects.requireNonNull(occurrenceId, "occurrenceId must not be null"));
        if (state == null) throw new IllegalArgumentException("unknown occurrenceId: " + occurrenceId);
        return state.result(specification);
    }

    public synchronized List<OutcomeResult> results() {
        return states.values().stream().map(state -> state.result(specification)).toList();
    }

    private static final class State {
        private final RuleOccurrence occurrence;
        private final long sequenceWatermark;
        private OutcomeStatus status = OutcomeStatus.OPEN;
        private FirstBarrier firstBarrier = FirstBarrier.UNRESOLVED;
        private TerminationReason terminationReason;
        private double mfe;
        private double mae;
        private long mfeTime = -1;
        private long maeTime = -1;
        private long targetTime = -1;
        private long stopTime = -1;
        private double finalPrice;
        private long finalTime = -1;
        private boolean hasFinalPrice;

        private State(RuleOccurrence occurrence, long sequenceWatermark) {
            this.occurrence = occurrence;
            this.sequenceWatermark = sequenceWatermark;
        }

        private void accept(PriceEvent event, OutcomeSpecification specification) {
            if (status != OutcomeStatus.OPEN
                    || !occurrence.symbol().equals(event.symbol())
                    || event.timeMsc() < occurrence.timeMsc()
                    || event.timeMsc() == occurrence.timeMsc() && event.sequence() <= sequenceWatermark) {
                if (!occurrence.symbol().equals(event.symbol())
                        && event.timeMsc() >= occurrence.timeMsc()) {
                    throw new IllegalArgumentException("price event symbol differs from occurrence symbol");
                }
                return;
            }

            long horizon = Math.addExact(occurrence.timeMsc(), specification.maxHorizon().toMillis());
            if (event.timeMsc() > horizon) {
                finishHorizon();
                return;
            }
            finalPrice = event.price();
            finalTime = event.timeMsc();
            hasFinalPrice = true;

            double signedMove = occurrence.direction() == RuleDirection.LONG
                    ? event.price() - occurrence.entryPrice()
                    : occurrence.entryPrice() - event.price();
            if (signedMove > mfe) {
                mfe = signedMove;
                mfeTime = event.timeMsc();
            }
            if (-signedMove > mae) {
                mae = -signedMove;
                maeTime = event.timeMsc();
            }

            boolean target = occurrence.direction() == RuleDirection.LONG
                    ? event.price() >= targetPrice(specification)
                    : event.price() <= targetPrice(specification);
            boolean stop = occurrence.direction() == RuleDirection.LONG
                    ? event.price() <= stopPrice(specification)
                    : event.price() >= stopPrice(specification);
            if (target && stop) {
                status = OutcomeStatus.UNRESOLVED;
                terminationReason = TerminationReason.SAME_EVENT_AMBIGUOUS;
                targetTime = event.timeMsc();
                stopTime = event.timeMsc();
            } else if (target) {
                status = OutcomeStatus.TARGET_FIRST;
                firstBarrier = FirstBarrier.TARGET;
                terminationReason = TerminationReason.TARGET;
                targetTime = event.timeMsc();
            } else if (stop) {
                status = OutcomeStatus.STOP_FIRST;
                firstBarrier = FirstBarrier.STOP;
                terminationReason = TerminationReason.STOP;
                stopTime = event.timeMsc();
            }
        }

        private void finishHorizon() {
            if (status == OutcomeStatus.OPEN) {
                status = OutcomeStatus.HORIZON_REACHED;
                terminationReason = TerminationReason.HORIZON_REACHED;
            }
        }

        private void finishReplay() {
            if (status == OutcomeStatus.OPEN) {
                status = OutcomeStatus.REPLAY_ENDED;
                terminationReason = TerminationReason.REPLAY_ENDED;
            }
        }

        private OutcomeResult result(OutcomeSpecification specification) {
            return new OutcomeResult(
                    occurrence, status, mfe, mae,
                    optionalDelta(mfeTime), optionalDelta(maeTime),
                    targetPrice(specification), stopPrice(specification),
                    optional(targetTime), optional(stopTime), firstBarrier,
                    hasFinalPrice ? OptionalDouble.of(finalPrice) : OptionalDouble.empty(),
                    optional(finalTime), terminationReason == null ? TerminationReason.OPEN : terminationReason);
        }

        private double targetPrice(OutcomeSpecification specification) {
            return occurrence.direction() == RuleDirection.LONG
                    ? occurrence.entryPrice() + specification.targetPoints()
                    : occurrence.entryPrice() - specification.targetPoints();
        }

        private double stopPrice(OutcomeSpecification specification) {
            return occurrence.direction() == RuleDirection.LONG
                    ? occurrence.entryPrice() - specification.stopPoints()
                    : occurrence.entryPrice() + specification.stopPoints();
        }

        private OptionalLong optionalDelta(long eventTime) {
            return eventTime < 0 ? OptionalLong.empty()
                    : OptionalLong.of(eventTime - occurrence.timeMsc());
        }

        private OptionalLong optional(long value) {
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        }
    }
}
