package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09Setup.Event;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09Setup.EventType;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09Setup.Pullback09Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Causal M5-only CRZ09, RJ09 and bearish pullback confirmation state machine. */
public final class Pullback09DownSetup {
    private static final double SMA9_INTERACTION_ZONE_POINTS = 30;

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
    private boolean currentStartedAboveSma9;
    private boolean currentStartedBelowSma9;
    private boolean currentObservedAboveSma9;
    private boolean currentCrossedDown;
    private boolean currentTouchedSma9;
    private double currentMinDistanceToSma9 = Double.NaN;
    private long candidateCandle1Bucket = -1;
    private double rejectionLow;
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
        currentStartedAboveSma9 = Double.isFinite(currentSma9)
                && currentOpenPrice > currentSma9;
        currentStartedBelowSma9 = Double.isFinite(currentSma9)
                && currentOpenPrice < currentSma9;
        currentObservedAboveSma9 = currentStartedAboveSma9;
        currentCrossedDown = false;
        currentTouchedSma9 = false;
        currentMinDistanceToSma9 = Double.isFinite(currentSma9)
                ? Math.abs(currentOpenPrice - currentSma9)
                : Double.NaN;
        observeDownwardCross(marketState);
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
        observeDownwardCross(marketState);
        observeSma9Retest(marketState);
    }

    private void observeDownwardCross(IntrabarMarketState marketState) {
        double average = sma9(marketState);
        if (!Double.isFinite(average)) return;
        if (marketState.price() > average) {
            currentObservedAboveSma9 = true;
        } else if (currentObservedAboveSma9 && marketState.price() < average) {
            currentCrossedDown = true;
        }
    }

    private void observeSma9Retest(IntrabarMarketState marketState) {
        double average = sma9(marketState);
        if (currentStartedBelowSma9 && Double.isFinite(average)
                && marketState.price() > currentOpenPrice
                && average - marketState.price() <= SMA9_INTERACTION_ZONE_POINTS) {
            currentTouchedSma9 = true;
        }
    }

    private void closeCurrentCandle(long availableAtTimeMsc, List<Event> events) {
        if (state == State.WAITING_FOR_CRZ09) {
            if (currentBucket < earliestCrzBucket) return;
            if (isCrz09()) {
                events.add(event(EventType.CRZ09_DOWN, availableAtTimeMsc, currentBucket, currentClose));
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
                events.add(event(EventType.RJ09_DOWN, availableAtTimeMsc, currentBucket, currentClose));
                rejectionLow = currentLow();
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
                && currentStartedAboveSma9 && currentCrossedDown
                && currentClose < currentSma9 && currentClose < currentCandle.open();
    }

    private boolean isRj09() {
        return Double.isFinite(currentSma9) && Double.isFinite(currentOpenSma9)
                && currentStartedBelowSma9 && currentTouchedSma9
                && currentClose < currentSma9 && currentClose > currentCandle.open();
    }

    private void confirmClosedCandle(long availableAtTimeMsc, List<Event> events) {
        long confirmationBucket = rejectionBucket + M5Bucket.DURATION_MSC;
        if (currentBucket == confirmationBucket
                && currentClose < currentCandle.open()
                && currentLow() < rejectionLow
                && currentClose < rejectionLow) {
            lastPullback09Context = new Pullback09Context(
                    rejectionCandle,
                    rejectionMinDistanceToSma9,
                    rejectionCandle.high(),
                    rejectionBucket);
            events.add(event(EventType.PULLB09_DOWN, availableAtTimeMsc,
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
        rejectionLow = 0;
        rejectionCandle = null;
        rejectionMinDistanceToSma9 = Double.NaN;
    }

    private double currentLow() {
        return currentCandle.low();
    }

    private double sma9(IntrabarMarketState marketState) {
        return marketState.sma9() == null ? Double.NaN : marketState.sma9();
    }
}
