package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;
import br.com.bauzin.market.panic.panicscanner.domain.replay.StructureReplayResult;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureSample;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Replays normalized samples; historical MT5 quote parity is intentionally a separate concern. */
public final class MarketStructureReplayRunner {
    private final MarketStructureEngine engine;
    private final LongSupplier nanoTime;

    public MarketStructureReplayRunner(MarketStructureEngine engine) {
        this(engine, System::nanoTime);
    }

    MarketStructureReplayRunner(MarketStructureEngine engine, LongSupplier nanoTime) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public StructureReplayResult run(ReplaySession session, ReplaySource<MarketStructureSample> source,
                                     StructureReplayObserver observer)
            throws IOException, InterruptedException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(source, "source");
        observer = observer == null ? StructureReplayObserver.NONE : observer;
        engine.reset();
        Accumulator accumulator = new Accumulator(session, observer);
        long start = nanoTime.getAsLong();
        try (source) {
            source.stream(accumulator::accept);
        }
        return accumulator.result(Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - start)));
    }

    private final class Accumulator {
        private final ReplaySession session;
        private final StructureReplayObserver observer;
        private long read, warmup, analysis, sma9, sma21, sma9Touched, sma21Touched,
                sma9Crossed, sma21Crossed, excluded;
        private long previousTime = -1;

        private Accumulator(ReplaySession session, StructureReplayObserver observer) {
            this.session = session;
            this.observer = observer;
        }

        private void accept(MarketStructureSample sample) {
            Objects.requireNonNull(sample, "source emitted null sample");
            read++;
            if (!session.symbol().equals(sample.symbol()))
                throw new IllegalArgumentException("Replay source symbol differs from session: " + sample.symbol());
            if (sample.timeMsc() < session.loadStartTimeMsc() || sample.timeMsc() > session.analysisEndTimeMsc())
                throw new IllegalArgumentException("Sample outside replay session: " + sample.timeMsc());
            if (previousTime > sample.timeMsc())
                throw new IllegalArgumentException("Replay source out of order: " + sample.timeMsc());
            previousTime = sample.timeMsc();
            var update = engine.onSample(sample);
            if (session.warmup(sample.timeMsc())) { warmup++; return; }
            if (!session.analysis(sample.timeMsc())) return;
            analysis++;
            for (MovingAverageInteraction interaction : update.completedInteractions()) {
                // Conservative policy: an interaction without a complete analyzed start is excluded.
                if (interaction.startTimeMsc() < session.analysisStartTimeMsc()) { excluded++; continue; }
                if (interaction.reference() == PriceReference.SMA9) {
                    sma9++;
                    if (interaction.touched()) sma9Touched++;
                    if (interaction.crossed()) sma9Crossed++;
                } else {
                    sma21++;
                    if (interaction.touched()) sma21Touched++;
                    if (interaction.crossed()) sma21Crossed++;
                }
                observer.onInteractionCompleted(interaction);
            }
        }

        private StructureReplayResult result(Duration elapsed) {
            return new StructureReplayResult(session, read, warmup, analysis, sma9, sma21,
                    sma9Touched, sma21Touched, sma9Crossed, sma21Crossed, excluded, elapsed);
        }
    }
}
