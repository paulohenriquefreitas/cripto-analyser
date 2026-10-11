package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.rules.IsRisingRule;
import org.ta4j.core.rules.IsFallingRule;
import br.com.bauzin.market.ta4j.rules.PreviousBarAppliedRule;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Closed-M5 EMA reversal research only; candidates are not entry signals. One instance per symbol/session. */
public final class Setup91 {
    /** Diagnostic direction classification only; native reversal rules use strict signs. */
    public static final double DEFAULT_RESEARCH_FLAT_SLOPE_THRESHOLD_POINTS = 1.0;
    public static final double CLOSE_CONFIRMATION_POINTS = 15.0;

    public enum Direction { UP, DOWN, FLAT }
    public enum Candidate { BUY, SELL }

    /**
     * NaN EMA / null slopes mean TA4J warm-up history is unavailable.
     * reversal is the native one-candle sign change used by execution candidates;
     * confirmedReversal is the independent, two-candle visual direction change.
     */
    public record Observation(IntrabarCandleSnapshot candle, long availableAtTimeMsc,
                              Double previousEma9, double ema9,
                              Double previousSlope, Double currentSlope,
                              Direction previousDirection, Direction direction,
                              Optional<Candidate> candidate, List<Double> previousFiveEma9,
                              Direction previousFiveDirection, Optional<Candidate> reversal,
                              Optional<Candidate> confirmedReversal) {
        public Observation { previousFiveEma9 = List.copyOf(previousFiveEma9); }
    }

    private final double researchFlatSlopeThresholdPoints;
    private final BarSeries closedSeries = new BaseBarSeriesBuilder().withName("setup91-closed-M5").build();
    private final EMAIndicator ema9 = new EMAIndicator(new ClosePriceIndicator(closedSeries), 9);
    private final Ema9ReversalDetector visualReversals = new Ema9ReversalDetector();
    // TA4J 0.22.6 expresses strict direction with strength 1.0, not a boolean.
    private final Rule rising = new IsRisingRule(ema9, 1, 1.0);
    private final Rule falling = new IsFallingRule(ema9, 1, 1.0);
    private final Rule changedToDown = new PreviousBarAppliedRule(rising, 1).and(falling);
    private final Rule changedToUp = new PreviousBarAppliedRule(falling, 1).and(rising);
    private Observation previous;

    public Setup91() {
        this(DEFAULT_RESEARCH_FLAT_SLOPE_THRESHOLD_POINTS);
    }

    public Setup91(double researchFlatSlopeThresholdPoints) {
        this(researchFlatSlopeThresholdPoints, false);
    }

    public Setup91(double researchFlatSlopeThresholdPoints, boolean atrDisplacementFilter) {
        if (!Double.isFinite(researchFlatSlopeThresholdPoints) || researchFlatSlopeThresholdPoints < 0) {
            throw new IllegalArgumentException("researchFlatSlopeThresholdPoints must be finite and nonnegative");
        }
        this.researchFlatSlopeThresholdPoints = researchFlatSlopeThresholdPoints;
        if (atrDisplacementFilter) throw new IllegalArgumentException("ATR nos fechamentos foi desativado; use ATR somente no risco da entrada");
    }

    /** Adapter for the existing causal processor: the developing candle and its price are never used. */
    public synchronized Optional<Observation> onEvent(IntrabarMarketState state) {
        return onEvent(state, true);
    }

    synchronized Optional<Observation> onEvent(IntrabarMarketState state, boolean signalsEnabled) {
        Objects.requireNonNull(state, "state");
        if (state.completedCandle() == null) return Optional.empty();
        return Optional.of(onCandleClosed(state.completedCandle(), state.timeMsc(), signalsEnabled));
    }

    /**
     * Accept an authoritative closed candle once, in order. Historical warm-up may use this same method
     * with availability at candle end and discard the returned observations before analysis starts.
     */
    public synchronized Observation onCandleClosed(IntrabarCandleSnapshot candle, long availableAtTimeMsc) {
        return onCandleClosed(candle, availableAtTimeMsc, true);
    }

    private Observation onCandleClosed(IntrabarCandleSnapshot candle, long availableAtTimeMsc, boolean signalsEnabled) {
        Objects.requireNonNull(candle, "candle");
        long end = candle.bucketStartTimeMsc() + M5Bucket.DURATION_MSC;
        if (availableAtTimeMsc < end) throw new IllegalArgumentException("M5 candle is not closed yet");
        if (previous != null && (!previous.candle().symbol().equals(candle.symbol())
                || candle.bucketStartTimeMsc() <= previous.candle().bucketStartTimeMsc()
                || availableAtTimeMsc < previous.availableAtTimeMsc())) {
            throw new IllegalArgumentException("Closed candles must be ordered, unique and same-symbol");
        }
        var n = closedSeries.numFactory();
        var begin = Instant.ofEpochMilli(candle.bucketStartTimeMsc());
        closedSeries.addBar(new BaseBar(Duration.ofMillis(M5Bucket.DURATION_MSC), begin,
                Instant.ofEpochMilli(end), n.numOf(candle.open()), n.numOf(candle.high()),
                n.numOf(candle.low()), n.numOf(candle.close()), n.zero(), n.zero(), 0L));
        int index = closedSeries.getEndIndex();
        boolean confirmedUp = false;
        boolean confirmedDown = false;
        if (index >= 1) {
            var currentBar = closedSeries.getBar(index);
            var previousBar = closedSeries.getBar(index - 1);
            var margin = n.numOf(CLOSE_CONFIRMATION_POINTS);
            confirmedUp = currentBar.getClosePrice().isGreaterThan(previousBar.getClosePrice().plus(margin));
            confirmedDown = currentBar.getClosePrice().isLessThan(previousBar.getClosePrice().minus(margin));
        }
        // Keep EMA observations continuous even when FDS rejects this candle: the next
        // reversal and the Replay EMA line must still use every closed candle.
        double currentEma = ema9.getValue(index).doubleValue();
        Double previousEma = previous == null || !Double.isFinite(previous.ema9()) ? null : previous.ema9();
        Double slope = previousEma == null || !Double.isFinite(currentEma) ? null : currentEma - previousEma;
        Direction direction = slope == null ? Direction.FLAT : direction(slope);
        Direction previousDirection = previous == null ? Direction.FLAT : previous.direction();
        // Diagnostic history only, not a signal filter. Oldest to newest, excluding the current candle.
        // Keep unavailable warm-up values
        // in place rather than skipping them and accidentally looking farther back.
        var previousFive = new ArrayList<Double>(5);
        for (int i = Math.max(0, index - 5); i < index; i++) {
            previousFive.add(ema9.getValue(i).doubleValue());
        }
        Direction previousFiveDirection = classifyPreviousFive(previousFive);
        Optional<Candidate> candidate = Optional.empty();
        Optional<Candidate> reversal = Optional.empty();
        // Native rules inspect only three consecutive finite CLOSED EMA points.
        // Diagnostic slope classifications do not gate candidates.
        if (index >= 2 && Double.isFinite(currentEma)
                && Double.isFinite(ema9.getValue(index - 1).doubleValue())
                && Double.isFinite(ema9.getValue(index - 2).doubleValue())) {
            if (changedToUp.isSatisfied(index)) reversal = Optional.of(Candidate.BUY);
            if (changedToDown.isSatisfied(index)) reversal = Optional.of(Candidate.SELL);
            if (signalsEnabled && ((confirmedUp && reversal.orElse(null) == Candidate.BUY)
                    || (confirmedDown && reversal.orElse(null) == Candidate.SELL))) candidate = reversal;
        }
        // Always advance visual direction state, including when this close fails confirmation.
        // Reject only this arrow; do not defer it to a later candle or alter lateral classification.
        var confirmedReversal = visualReversals.onClosedEma(candle.bucketStartTimeMsc(), currentEma);
        if ((confirmedReversal.orElse(null) == Candidate.BUY && !confirmedUp)
                || (confirmedReversal.orElse(null) == Candidate.SELL && !confirmedDown)) {
            confirmedReversal = Optional.empty();
        }
        Observation observation = new Observation(candle, availableAtTimeMsc, previousEma, currentEma,
                previous == null ? null : previous.currentSlope(), slope, previousDirection, direction, candidate,
                previousFive, previousFiveDirection, reversal,
                confirmedReversal);
        previous = observation;
        return observation;
    }

    public double researchFlatSlopeThresholdPoints() {
        return researchFlatSlopeThresholdPoints;
    }


    private static Direction classifyPreviousFive(List<Double> values) {
        if (values.size() != 5 || values.stream().anyMatch(value -> !Double.isFinite(value))) return Direction.FLAT;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < values.size(); i++) {
            up &= values.get(i) > values.get(i - 1);
            down &= values.get(i) < values.get(i - 1);
        }
        return up ? Direction.UP : down ? Direction.DOWN : Direction.FLAT;
    }

    private Direction direction(double slope) {
        if (slope > researchFlatSlopeThresholdPoints) return Direction.UP;
        if (slope < -researchFlatSlopeThresholdPoints) return Direction.DOWN;
        return Direction.FLAT;
    }
}
