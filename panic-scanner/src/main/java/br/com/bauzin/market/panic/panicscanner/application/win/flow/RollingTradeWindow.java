package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;

final class RollingTradeWindow {
    private final Duration duration;
    private final long durationMsc;
    private final double durationSeconds;
    private final Deque<MarketTrade> trades = new ArrayDeque<>();
    private final Deque<MarketTrade> minima = new ArrayDeque<>();
    private final Deque<MarketTrade> maxima = new ArrayDeque<>();

    private double buyVolume;
    private double sellVolume;
    private double ambiguousVolume;
    private long referenceTimeMsc = -1;

    RollingTradeWindow(Duration duration) {
        this.duration = duration;
        this.durationMsc = duration.toMillis();
        this.durationSeconds = duration.toNanos() / 1_000_000_000.0;
    }

    void add(MarketTrade trade) {
        trades.addLast(trade);
        addVolume(trade);
        while (!minima.isEmpty() && minima.peekLast().price() > trade.price()) {
            minima.removeLast();
        }
        minima.addLast(trade);
        while (!maxima.isEmpty() && maxima.peekLast().price() < trade.price()) {
            maxima.removeLast();
        }
        maxima.addLast(trade);
        referenceTimeMsc = Math.max(referenceTimeMsc, trade.timeMsc());
        expireAt(referenceTimeMsc);
    }

    FlowSnapshot snapshot() {
        if (trades.isEmpty()) {
            return FlowSnapshot.empty(duration);
        }
        MarketTrade first = trades.peekFirst();
        MarketTrade last = trades.peekLast();
        double totalVolume = buyVolume + sellVolume + ambiguousVolume;
        double knownVolume = buyVolume + sellVolume;
        double knownDelta = buyVolume - sellVolume;
        double buyShare = knownVolume == 0 ? 0 : buyVolume / knownVolume;
        double sellShare = knownVolume == 0 ? 0 : sellVolume / knownVolume;
        double firstPrice = first.price();
        double lastPrice = last.price();
        double minPrice = minima.peekFirst().price();
        double maxPrice = maxima.peekFirst().price();
        double priceChange = lastPrice - firstPrice;
        double priceRange = maxPrice - minPrice;
        long toTimeMsc = referenceTimeMsc;
        return new FlowSnapshot(
                duration,
                toTimeMsc - durationMsc,
                toTimeMsc,
                trades.size(),
                totalVolume,
                buyVolume,
                sellVolume,
                ambiguousVolume,
                knownVolume,
                knownDelta,
                buyShare,
                sellShare,
                trades.size() / durationSeconds,
                totalVolume / durationSeconds,
                OptionalDouble.of(firstPrice),
                OptionalDouble.of(lastPrice),
                OptionalDouble.of(minPrice),
                OptionalDouble.of(maxPrice),
                OptionalDouble.of(priceChange),
                OptionalDouble.of(priceRange),
                OptionalDouble.of(priceChange / durationSeconds));
    }

    void clear() {
        referenceTimeMsc = -1;
        trades.clear();
        minima.clear();
        maxima.clear();
        buyVolume = 0;
        sellVolume = 0;
        ambiguousVolume = 0;
    }

    FlowSnapshot snapshotAt(long timeMsc) {
        if (timeMsc < referenceTimeMsc) throw new IllegalArgumentException("window clock cannot go backwards");
        referenceTimeMsc = timeMsc;
        expireAt(timeMsc);
        if (!trades.isEmpty()) return snapshot();
        var empty = FlowSnapshot.empty(duration);
        return new FlowSnapshot(duration, timeMsc - durationMsc, timeMsc, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                empty.firstPrice(), empty.lastPrice(), empty.minPrice(), empty.maxPrice(),
                empty.priceChange(), empty.priceRange(), empty.priceVelocity());
    }

    private void expireAt(long currentTimeMsc) {
        long lowerExclusive = currentTimeMsc - durationMsc;
        while (!trades.isEmpty() && trades.peekFirst().timeMsc() <= lowerExclusive) {
            MarketTrade expired = trades.removeFirst();
            removeVolume(expired);
            if (minima.peekFirst() == expired) {
                minima.removeFirst();
            }
            if (maxima.peekFirst() == expired) {
                maxima.removeFirst();
            }
        }
    }

    private void addVolume(MarketTrade trade) {
        switch (trade.side()) {
            case BUY -> buyVolume += trade.volume();
            case SELL -> sellVolume += trade.volume();
            case AMBIGUOUS -> ambiguousVolume += trade.volume();
        }
    }

    private void removeVolume(MarketTrade trade) {
        switch (trade.side()) {
            case BUY -> buyVolume = Math.max(0, buyVolume - trade.volume());
            case SELL -> sellVolume = Math.max(0, sellVolume - trade.volume());
            case AMBIGUOUS -> ambiguousVolume = Math.max(0, ambiguousVolume - trade.volume());
        }
    }
}
