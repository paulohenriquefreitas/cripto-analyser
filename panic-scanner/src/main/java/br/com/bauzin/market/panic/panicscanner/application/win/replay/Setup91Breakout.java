package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Research-only signal candles and first LAST breakouts. One owner per ordered symbol stream. */
public final class Setup91Breakout {
    public enum EventType {
        SETUP91_BUY_ARMED, SETUP91_BUY_TRIGGERED, SETUP91_BUY_CANCELLED,
        SETUP91_SELL_ARMED, SETUP91_SELL_TRIGGERED, SETUP91_SELL_CANCELLED
    }

    public record SignalCandle(Setup91.Candidate side, IntrabarCandleSnapshot candle,
                               double ema9, double previousSlope, double currentSlope,
                               List<Double> previousFiveEma9, Setup91.Direction previousFiveDirection) {
        public SignalCandle { previousFiveEma9 = List.copyOf(previousFiveEma9); }
    }

    /** List position orders ARMED before TRIGGERED when both arise from the same LAST. */
    public record Event(EventType type, SignalCandle signal, long timeMsc, long sequence, double lastPrice) {}

    public record Update(Optional<Setup91.Observation> observation, List<Event> events) {}

    private final Setup91 detector;
    private final List<SignalCandle> armed = new ArrayList<>();
    private String symbol;
    private long lastTimeMsc = -1;
    private long lastSequence = -1;

    public Setup91Breakout() {
        this(Setup91.DEFAULT_RESEARCH_FLAT_SLOPE_THRESHOLD_POINTS);
    }

    public Setup91Breakout(double researchFlatSlopeThresholdPoints) {
        this(new Setup91(researchFlatSlopeThresholdPoints));
    }

    /** Transfers ownership of a detector seeded only with closed warm-up candles; no warm-up setups are armed. */
    Setup91Breakout(Setup91 detector) {
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    /**
     * Supply each IntrabarM5Processor output once with its original LAST sequence (or a monotonic
     * arrival ordinal for a live source). Equal timestamps are valid; sequence must increase.
     * Pending N signals expire before processing the next closure or testing any N+2 LAST.
     * Only the immediately following calendar M5 bucket (N+1) can execute a signal.
     */
    public synchronized Update onEvent(IntrabarMarketState state, long sequence) {
        return onEvent(state, sequence, false);
    }

    public synchronized Update onEvent(IntrabarMarketState state, long sequence, boolean positionOpen) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(state.candle(), "state.candle");
        CanonicalPriceEvent.requirePrice(state.price());
        long bucket = M5Bucket.start(state.timeMsc());
        if (sequence <= lastSequence || state.timeMsc() < lastTimeMsc
                || (symbol != null && !symbol.equals(state.symbol()))
                || !state.candle().symbol().equals(state.symbol())
                || state.candle().bucketStartTimeMsc() != bucket) {
            throw new IllegalArgumentException("Expected same-symbol LASTs in timestamp and increasing sequence order");
        }
        var completed = state.completedCandle();
        if (completed != null && (!completed.symbol().equals(state.symbol())
                || completed.bucketStartTimeMsc() >= bucket)) {
            throw new IllegalArgumentException("Signal candle must precede the current LAST bucket");
        }

        var events = new ArrayList<Event>();
        if (positionOpen) {
            // Never retain a signal armed before entry or calculate signals during a position.
            armed.clear();
            var observation = detector.onEvent(state, false);
            symbol = state.symbol();
            lastTimeMsc = state.timeMsc();
            lastSequence = sequence;
            return new Update(observation, List.of());
        }
        // All LASTs in N+1 have already been processed. Expire before this rollover LAST
        // can test the old extreme, independently of the execution candle's EMA direction.
        var pending = armed.iterator();
        while (pending.hasNext()) {
            var signal = pending.next();
            if (bucket > executionBucket(signal)) {
                events.add(cancelled(signal, state, sequence));
                pending.remove();
            }
        }
        var observation = detector.onEvent(state);
        if (observation.isPresent() && observation.get().candidate().isPresent()) {
            var value = observation.get();
            var signal = new SignalCandle(value.candidate().orElseThrow(), value.candle(),
                    value.ema9(), value.previousSlope(), value.currentSlope(),
                    value.previousFiveEma9(), value.previousFiveDirection());
            events.add(event(signal, false, state, sequence));
            // A gap may reveal Candle N's closure only after N+1 has already ended.
            // Preserve the reversal event, but never arm it for a later execution bucket.
            if (bucket > executionBucket(signal)) events.add(cancelled(signal, state, sequence));
            else armed.add(signal);
        }
        var iterator = armed.iterator();
        while (iterator.hasNext()) {
            var signal = iterator.next();
            boolean inExecutionWindow = bucket == executionBucket(signal);
            boolean breakout = signal.side() == Setup91.Candidate.BUY
                    ? state.price() > signal.candle().high() : state.price() < signal.candle().low();
            if (inExecutionWindow && breakout) {
                events.add(event(signal, true, state, sequence));
                iterator.remove();
            }
        }
        symbol = state.symbol();
        lastTimeMsc = state.timeMsc();
        lastSequence = sequence;
        return new Update(observation, List.copyOf(events));
    }

    public synchronized List<SignalCandle> armedSignals() {
        return List.copyOf(armed);
    }

    private static long executionBucket(SignalCandle signal) {
        return signal.candle().bucketStartTimeMsc() + M5Bucket.DURATION_MSC;
    }

    private static Event cancelled(SignalCandle signal, IntrabarMarketState state, long sequence) {
        return new Event(signal.side() == Setup91.Candidate.BUY
                ? EventType.SETUP91_BUY_CANCELLED : EventType.SETUP91_SELL_CANCELLED,
                signal, state.timeMsc(), sequence, state.price());
    }

    private static Event event(SignalCandle signal, boolean triggered, IntrabarMarketState state, long sequence) {
        EventType type = signal.side() == Setup91.Candidate.BUY
                ? (triggered ? EventType.SETUP91_BUY_TRIGGERED : EventType.SETUP91_BUY_ARMED)
                : (triggered ? EventType.SETUP91_SELL_TRIGGERED : EventType.SETUP91_SELL_ARMED);
        return new Event(type, signal, state.timeMsc(), sequence, state.price());
    }
}
