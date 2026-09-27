package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

public final class TradeReplayRunner {
    private final TradeFlowEngine engine;
    private final LongSupplier nanoTime;

    public TradeReplayRunner(TradeFlowEngine engine) {
        this(engine, System::nanoTime);
    }

    TradeReplayRunner(TradeFlowEngine engine, LongSupplier nanoTime) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public ReplayRunResult run(br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession session, ReplaySource<MarketTrade> source,
                               TradeReplayObserver observer) throws IOException, InterruptedException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(source, "source");
        observer = observer == null ? TradeReplayObserver.NONE : observer;
        engine.reset();
        Accumulator accumulator = new Accumulator(session, observer);
        long started = nanoTime.getAsLong();
        try (source) {
            source.stream(accumulator::accept);
        }
        long elapsedNanos = Math.max(0, nanoTime.getAsLong() - started);
        return accumulator.result(Duration.ofNanos(elapsedNanos));
    }

    private final class Accumulator {
        private final br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession session;
        private final TradeReplayObserver observer;
        private long read, processed, warmup, analysis, buy, sell, ambiguous;
        private double totalVolume, buyVolume, sellVolume, ambiguousVolume;
        private double firstPrice, lastPrice, minPrice = Double.POSITIVE_INFINITY, maxPrice = Double.NEGATIVE_INFINITY;
        private long firstTime, lastTime;
        private long previousTime = -1;

        private Accumulator(br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession session, TradeReplayObserver observer) {
            this.session = session;
            this.observer = observer;
        }

        private void accept(MarketTrade trade) {
            Objects.requireNonNull(trade, "source emitted null trade");
            read++;
            if (!session.symbol().equals(trade.symbol()))
                throw new IllegalArgumentException("Replay source symbol differs from session: " + trade.symbol());
            if (trade.timeMsc() < session.loadStartTimeMsc() || trade.timeMsc() > session.analysisEndTimeMsc())
                throw new IllegalArgumentException("Trade outside replay session: " + trade.timeMsc());
            if (previousTime > trade.timeMsc())
                throw new IllegalArgumentException("Replay source out of order: " + trade.timeMsc() + " < " + previousTime);
            engine.onTrade(trade);
            previousTime = trade.timeMsc();
            processed++;
            if (session.warmup(trade.timeMsc())) { warmup++; return; }
            if (!session.analysis(trade.timeMsc())) return;
            analysis++;
            accumulate(trade);
            observer.onTrade(trade);
            if (observer.observeFlowSnapshots()) {
                observer.onFlowSnapshots(trade, new ReplayFlowSnapshots(
                        engine.snapshot(TradeFlowEngine.ONE_SECOND),
                        engine.snapshot(TradeFlowEngine.THREE_SECONDS),
                        engine.snapshot(TradeFlowEngine.FIVE_SECONDS),
                        engine.snapshot(TradeFlowEngine.TEN_SECONDS)));
            }
        }

        private void accumulate(MarketTrade trade) {
            if (analysis == 1) { firstPrice = trade.price(); firstTime = trade.timeMsc(); }
            lastPrice = trade.price();
            lastTime = trade.timeMsc();
            minPrice = Math.min(minPrice, trade.price());
            maxPrice = Math.max(maxPrice, trade.price());
            totalVolume += trade.volume();
            if (trade.side() == AggressorSide.BUY) { buy++; buyVolume += trade.volume(); }
            else if (trade.side() == AggressorSide.SELL) { sell++; sellVolume += trade.volume(); }
            else { ambiguous++; ambiguousVolume += trade.volume(); }
        }

        private ReplayRunResult result(Duration wallClock) {
            OptionalDouble emptyDouble = OptionalDouble.empty();
            OptionalLong emptyLong = OptionalLong.empty();
            TradeReplayStats stats = new TradeReplayStats(analysis, buy, sell, ambiguous,
                    totalVolume, buyVolume, sellVolume, ambiguousVolume, buyVolume - sellVolume,
                    analysis == 0 ? emptyDouble : OptionalDouble.of(firstPrice),
                    analysis == 0 ? emptyDouble : OptionalDouble.of(lastPrice),
                    analysis == 0 ? emptyDouble : OptionalDouble.of(minPrice),
                    analysis == 0 ? emptyDouble : OptionalDouble.of(maxPrice),
                    analysis == 0 ? emptyLong : OptionalLong.of(firstTime),
                    analysis == 0 ? emptyLong : OptionalLong.of(lastTime));
            double seconds = wallClock.toNanos() / 1_000_000_000.0;
            double rate = seconds == 0 ? 0 : processed / seconds;
            return new ReplayRunResult(session, read, processed, warmup, analysis,
                    Duration.ofMillis(session.analysisEndTimeMsc() - session.analysisStartTimeMsc()),
                    wallClock, rate, stats);
        }
    }
}
