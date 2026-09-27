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
    private long candidateCandle1Bucket = -1;
    private double rejectionHigh;
    private long rejectionBucket = -1;
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

        confirmBreakout(marketState, events);
        lastTimeMsc = marketState.timeMsc();
        return List.copyOf(events);
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
        observeUpwardCross(marketState);
        observeSma9Retest(marketState);
    }

    private void updateCurrentCandle(IntrabarMarketState marketState) {
        currentCandle = marketState.candle();
        currentClose = marketState.candle().close();
        currentSma9 = sma9(marketState);
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
                state = State.WAITING_FOR_CONFIRMATION;
            } else {
                resetCandidate();
            }
            return;
        }

        if (state == State.WAITING_FOR_CONFIRMATION
                && currentBucket >= rejectionBucket + M5Bucket.DURATION_MSC) {
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

    private void confirmBreakout(IntrabarMarketState marketState, List<Event> events) {
        if (state != State.WAITING_FOR_CONFIRMATION) return;
        long confirmationBucket = rejectionBucket + M5Bucket.DURATION_MSC;
        long observedBucket = marketState.candle().bucketStartTimeMsc();
        if (observedBucket > confirmationBucket) {
            resetCandidate();
            return;
        }
        if (observedBucket == confirmationBucket && marketState.price() > rejectionHigh) {
            events.add(event(EventType.PULLB09_UP, marketState.timeMsc(),
                    observedBucket, marketState.price()));
            resetCandidate();
            earliestCrzBucket = observedBucket + M5Bucket.DURATION_MSC;
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
    }

    private double currentHigh() {
        return currentCandle.high();
    }

    private double sma9(IntrabarMarketState marketState) {
        return marketState.sma9() == null ? Double.NaN : marketState.sma9();
    }
}
