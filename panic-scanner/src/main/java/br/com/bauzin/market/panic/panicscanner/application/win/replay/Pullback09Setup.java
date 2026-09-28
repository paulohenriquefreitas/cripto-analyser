package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Causal M5-only CRZ09, RJ09 and bullish pullback confirmation state machine. */
public final class Pullback09Setup {
    private static final double SMA9_INTERACTION_ZONE_POINTS = 30;

    public enum EventType { CRZ09_UP, RJ09_UP, PULLB09_UP }

    public record Event(String eventId, EventType eventType, String symbol,
                        long timeMsc, long candleTimeMsc, double price) {}

    public record Pullback09Context(
            IntrabarCandleSnapshot rejectionCandle,
            double rejectionMinDistanceToSma9,
            double rejectionHigh,
            long rejectionBucket) {}

    private enum State { WAITING_FOR_CRZ09, WAITING_FOR_RJ09, WAITING_FOR_CONFIRMATION }

    private State state = State.WAITING_FOR_CRZ09;
    private String symbol;
    private long lastTimeMsc = -1;
    private long currentBucket = -1;
    private double currentSma9 = Double.NaN;
    private double currentOpenSma9 = Double.NaN;
    private double currentOpenPrice = Double.NaN;
    private double currentClose = Double.NaN;
    private IntrabarCandleSnapshot currentCandle;
    private boolean currentStartedBelowSma9;
    private boolean currentStartedAboveSma9;
    private boolean currentObservedBelowSma9;
    private boolean currentCrossedUp;
    private boolean currentTouchedSma9;
    private double currentMinDistanceToSma9 = Double.NaN;
    private long candidateCandle1Bucket = -1;
    private double rejectionHigh;
    private long rejectionBucket = -1;
    private IntrabarCandleSnapshot rejectionCandle;
    private double rejectionMinDistanceToSma9 = Double.NaN;
    private Pullback09Context lastPullback09Context;
    private long earliestCrzBucket = -1;
    private long occurrenceNumber;

    public synchronized List<Event> onEvent(IntrabarMarketState marketState) {
        Objects.requireNonNull(marketState, "marketState must not be null");
        if (symbol != null && !symbol.equals(marketState.symbol())) {
            throw new IllegalArgumentException("Cannot mix symbols without reset");
        }
        if (marketState.timeMsc() < lastTimeMsc) {
            throw new IllegalArgumentException("Setup events must be in chronological order");
        }

        List<Event> events = new ArrayList<>(3);
        long bucket = marketState.candle().bucketStartTimeMsc();
        if (currentBucket < 0) {
            symbol = marketState.symbol();
            beginCandle(marketState);
        } else if (bucket > currentBucket) {
            closeCurrentCandle(marketState.timeMsc(), events);
            beginCandle(marketState);
        } else if (bucket < currentBucket) {
            throw new IllegalArgumentException("M5 candles must be in chronological order");
        } else {
            updateCurrentCandle(marketState);
        }

        lastTimeMsc = marketState.timeMsc();
        return List.copyOf(events);
    }

    public synchronized Pullback09Context lastPullback09Context() {
        return lastPullback09Context;
    }

    private void beginCandle(IntrabarMarketState marketState) {
        currentBucket = marketState.candle().bucketStartTimeMsc();
        currentCandle = marketState.candle();
        currentOpenPrice = marketState.candle().open();
        currentClose = marketState.candle().close();
        currentSma9 = sma9(marketState);
        currentOpenSma9 = currentSma9;
        currentStartedBelowSma9 = Double.isFinite(currentSma9)
                && currentOpenPrice < currentSma9;
        currentStartedAboveSma9 = Double.isFinite(currentSma9)
                && currentOpenPrice > currentSma9;
        currentObservedBelowSma9 = currentStartedBelowSma9;
        currentCrossedUp = false;
        currentTouchedSma9 = false;
        currentMinDistanceToSma9 = Double.isFinite(currentSma9)
                ? Math.abs(currentOpenPrice - currentSma9)
                : Double.NaN;
        observeUpwardCross(marketState);
        observeSma9Retest(marketState);
    }

    private void updateCurrentCandle(IntrabarMarketState marketState) {
        currentCandle = marketState.candle();
        currentClose = marketState.candle().close();
        currentSma9 = sma9(marketState);
        if (Double.isFinite(currentSma9)) {
            double dist = Math.abs(marketState.price() - currentSma9);
            currentMinDistanceToSma9 = Double.isNaN(currentMinDistanceToSma9)
                    ? dist
                    : Math.min(currentMinDistanceToSma9, dist);
        }
        observeUpwardCross(marketState);
        observeSma9Retest(marketState);
    }

    private void observeUpwardCross(IntrabarMarketState marketState) {
        double average = sma9(marketState);
        if (!Double.isFinite(average)) return;
        if (marketState.price() < average) {
            currentObservedBelowSma9 = true;
        } else if (currentObservedBelowSma9 && marketState.price() > average) {
            currentCrossedUp = true;
        }
    }

    private void observeSma9Retest(IntrabarMarketState marketState) {
        double average = sma9(marketState);
        if (currentStartedAboveSma9 && Double.isFinite(average)
                && marketState.price() < currentOpenPrice
                && marketState.price() - average <= SMA9_INTERACTION_ZONE_POINTS) {
            currentTouchedSma9 = true;
        }
    }

    private void closeCurrentCandle(long availableAtTimeMsc, List<Event> events) {
        if (state == State.WAITING_FOR_CRZ09) {
            if (currentBucket < earliestCrzBucket) return;
            if (isCrz09()) {
                events.add(event(EventType.CRZ09_UP, availableAtTimeMsc, currentBucket, currentClose));
                state = State.WAITING_FOR_RJ09;
                candidateCandle1Bucket = currentBucket;
                earliestCrzBucket = -1;
            }
            return;
        }

        if (state == State.WAITING_FOR_RJ09) {
            if (currentBucket != candidateCandle1Bucket + M5Bucket.DURATION_MSC) {
                resetCandidate();
                return;
            }
            if (isRj09()) {
                events.add(event(EventType.RJ09_UP, availableAtTimeMsc, currentBucket, currentClose));
                rejectionHigh = currentHigh();
                rejectionBucket = currentBucket;
                rejectionCandle = currentCandle;
                rejectionMinDistanceToSma9 = currentMinDistanceToSma9;
                state = State.WAITING_FOR_CONFIRMATION;
            } else {
                resetCandidate();
            }
            return;
        }

        if (state == State.WAITING_FOR_CONFIRMATION
                && currentBucket >= rejectionBucket + M5Bucket.DURATION_MSC) {
            confirmClosedCandle(availableAtTimeMsc, events);
            resetCandidate();
        }
    }

    private boolean isCrz09() {
        return Double.isFinite(currentSma9) && Double.isFinite(currentOpenSma9)
                && currentStartedBelowSma9 && currentCrossedUp
                && currentClose > currentSma9 && currentClose > currentCandle.open();
    }

    private boolean isRj09() {
        return Double.isFinite(currentSma9) && Double.isFinite(currentOpenSma9)
                && currentStartedAboveSma9 && currentTouchedSma9
                && currentClose > currentSma9 && currentClose < currentCandle.open();
    }

    private void confirmClosedCandle(long availableAtTimeMsc, List<Event> events) {
        long confirmationBucket = rejectionBucket + M5Bucket.DURATION_MSC;
        if (currentBucket == confirmationBucket
                && currentClose > currentCandle.open()
                && currentHigh() > rejectionHigh
                && currentClose > rejectionHigh) {
            lastPullback09Context = new Pullback09Context(
                    rejectionCandle,
                    rejectionMinDistanceToSma9,
                    rejectionHigh,
                    rejectionBucket);
            events.add(event(EventType.PULLB09_UP, availableAtTimeMsc,
                    currentBucket, currentClose));
            earliestCrzBucket = currentBucket + M5Bucket.DURATION_MSC;
        }
    }

    private Event event(EventType eventType, long timeMsc, long candleTimeMsc, double price) {
        return new Event("PULLB09-" + eventType + "-" + occurrenceNumber++,
                eventType, symbol, timeMsc, candleTimeMsc, price);
    }

    private void resetCandidate() {
        state = State.WAITING_FOR_CRZ09;
        candidateCandle1Bucket = -1;
        rejectionBucket = -1;
        rejectionHigh = 0;
        rejectionCandle = null;
        rejectionMinDistanceToSma9 = Double.NaN;
    }

    private double currentHigh() {
        return currentCandle.high();
    }

    private double sma9(IntrabarMarketState marketState) {
        return marketState.sma9() == null ? Double.NaN : marketState.sma9();
    }
}
