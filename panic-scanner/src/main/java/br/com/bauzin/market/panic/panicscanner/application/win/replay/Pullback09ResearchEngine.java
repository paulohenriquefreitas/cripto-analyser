package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Causal quantitative research engine for PULLB09 setups.
 * Captures point-in-time snapshots with zero look-ahead and tracks post-signal outcomes.
 */
public final class Pullback09ResearchEngine {
    private final BarSeries closedSeries = new BaseBarSeriesBuilder().withName("pullback09-research-M5").build();
    private final ClosePriceIndicator closePrice = new ClosePriceIndicator(closedSeries);
    private final SMAIndicator sma9Indicator = new SMAIndicator(closePrice, 9);
    private final SMAIndicator sma21Indicator = new SMAIndicator(closePrice, 21);
    private final ATRIndicator atr14Indicator = new ATRIndicator(closedSeries, 14);

    private final Pullback09SetupOutcomeTracker outcomeTracker = new Pullback09SetupOutcomeTracker();
    private final List<Pullback09ResearchSnapshot> snapshots = new ArrayList<>();
    private final List<Consumer<Pullback09ResearchSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();

    public Pullback09ResearchEngine() {
        closedSeries.setMaximumBarCount(1000);
    }

    public synchronized void warmUp(String symbol, List<Mt5Candle> warmupCandles) {
        for (Mt5Candle c : warmupCandles) {
            addClosedBar(c.time() * 1000L, c.open(), c.high(), c.low(), c.close(), c.realVolume());
        }
    }

    public synchronized void onCandleClosed(IntrabarCandleSnapshot closedCandle) {
        if (closedCandle == null) return;
        addClosedBar(
                closedCandle.bucketStartTimeMsc(),
                closedCandle.open(),
                closedCandle.high(),
                closedCandle.low(),
                closedCandle.close(),
                0
        );
    }

    public synchronized Pullback09ResearchSnapshot onPullback09(
            Pullback09Setup.Event event,
            Pullback09Setup.Pullback09Context context,
            IntrabarMarketState marketState,
            Double sessionVwap) {
        Objects.requireNonNull(context, "RJ09 context required");
        IntrabarCandleSnapshot confirmation = marketState.completedCandle();
        if (event.eventType() != Pullback09Setup.EventType.PULLB09_UP
                || event.timeMsc() != marketState.timeMsc()
                || confirmation == null
                || confirmation.bucketStartTimeMsc() != event.candleTimeMsc()
                || marketState.candle().bucketStartTimeMsc() <= event.candleTimeMsc()) {
            throw new IllegalArgumentException("Research requires the first LAST of Candle 4 and closed Candle 3");
        }
        for (Pullback09ResearchSnapshot existing : snapshots) {
            if (existing.eventId().equals(event.eventId())) return existing;
        }
        int end = closedSeries.getEndIndex();
        Double atr = closedSeries.getBarCount() >= 14 ? atr14Indicator.getValue(end).doubleValue() : null;
        // Difference between the latest closed SMA and the SMA three closed bars earlier.
        // No developing Candle 4 data participates in either endpoint.
        Double slope9 = closedSeries.getBarCount() >= 12
                ? sma9Indicator.getValue(end).doubleValue() - sma9Indicator.getValue(end - 3).doubleValue() : null;
        Double slope21 = closedSeries.getBarCount() >= 24
                ? sma21Indicator.getValue(end).doubleValue() - sma21Indicator.getValue(end - 3).doubleValue() : null;
        Double distance = sessionVwap == null ? null : marketState.price() - sessionVwap;
        Pullback09ResearchSnapshot snapshot = new Pullback09ResearchSnapshot(
                event.eventId(), event.symbol(), event.candleTimeMsc(), event.timeMsc(),
                marketState.price(), marketState.candle().bucketStartTimeMsc(),
                context.rejectionCandle(), context.rejectionMinDistanceToSma9(), confirmation,
                confirmation.close() - context.rejectionHigh(), marketState.sma9(), marketState.sma21(),
                marketState.sma9() == null || marketState.sma21() == null ? null : marketState.sma9() - marketState.sma21(),
                slope9, slope21, sessionVwap, distance, atr);
        snapshots.add(snapshot);
        outcomeTracker.register(snapshot);
        snapshotListeners.forEach(listener -> listener.accept(snapshot));
        return snapshot;
    }

    public synchronized void onPrice(CanonicalPriceEvent event) {
        if (event == null) return;
        outcomeTracker.onPrice(event);
    }

    public synchronized void finish() {
        outcomeTracker.finish();
    }

    public synchronized List<Pullback09ResearchSnapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public synchronized List<Pullback09ResearchRecord> researchRecords() {
        return outcomeTracker.records();
    }

    public synchronized String toCsv() {
        return Pullback09ResearchRecord.toCsv(outcomeTracker.records());
    }

    public void addSnapshotListener(Consumer<Pullback09ResearchSnapshot> listener) {
        snapshotListeners.add(listener);
    }

    public void removeSnapshotListener(Consumer<Pullback09ResearchSnapshot> listener) {
        snapshotListeners.remove(listener);
    }

    private void addClosedBar(long bucketMsc, double open, double high, double low, double close, double volume) {
        var n = closedSeries.numFactory();
        Instant begin = Instant.ofEpochMilli(bucketMsc);
        closedSeries.addBar(new BaseBar(
                Duration.ofMinutes(5),
                begin,
                begin.plus(Duration.ofMinutes(5)),
                n.numOf(open),
                n.numOf(high),
                n.numOf(low),
                n.numOf(close),
                n.numOf(volume),
                n.zero(),
                0L
        ));
    }
}
