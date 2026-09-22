package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureSample;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketStructureReplayRunnerTest {
    @Test void replayProducesExactlySameMovingAverageInteractionsAsDirectInput() throws Exception {
        List<MarketStructureSample> samples = samples(100, 50, 35, 20, 8, 2, -5, 4, 15, 25);
        MarketStructureEngine direct = new MarketStructureEngine();
        List<MovingAverageInteraction> expected = new ArrayList<>();
        samples.forEach(sample -> expected.addAll(direct.onSample(sample).completedInteractions()));
        List<MovingAverageInteraction> replayed = new ArrayList<>();
        var result = new MarketStructureReplayRunner(new MarketStructureEngine()).run(
                new ReplaySession("WINV26", 100, 100, 108), source(samples), replayed::add);
        assertEquals(expected, replayed);
        assertEquals(1, result.sma21Interactions());
        assertEquals(1, result.sma21Touched());
        assertEquals(1, result.sma21Crossed());
    }

    @Test void movingAverageValueFromEverySampleControlsDistance() throws Exception {
        List<MarketStructureSample> samples = List.of(
                sample(0, 188750, 188700), sample(1, 188730, 188701),
                sample(2, 188715, 188703), sample(3, 188706, 188704),
                sample(4, 188701, 188705), sample(5, 188710, 188706),
                sample(6, 188725, 188708), sample(7, 188730, 188709));
        List<MovingAverageInteraction> replayed = new ArrayList<>();
        new MarketStructureReplayRunner(new MarketStructureEngine()).run(
                new ReplaySession("WINV26", 0, 0, 7), source(samples), replayed::add);
        MovingAverageInteraction sma21 = replayed.stream()
                .filter(i -> i.reference() == PriceReference.SMA21).findFirst().orElseThrow();
        assertEquals(2, sma21.minimumAbsoluteDistance());
        assertEquals(4, sma21.maxPenetrationPoints());
        assertEquals(188709, sma21.movingAverageAtExit());
    }

    @Test void sma9AndSma21RemainIndependentDuringReplay() throws Exception {
        List<MarketStructureSample> samples = new ArrayList<>();
        double[] distances = {50, 35, 20, 8, 2, -5, 4, 15, 25};
        for (int i = 0; i < distances.length; i++) {
            double price = 188700 + distances[i];
            samples.add(new MarketStructureSample("WINV26", i, price, 188700, 188650));
        }
        List<MovingAverageInteraction> replayed = new ArrayList<>();
        new MarketStructureReplayRunner(new MarketStructureEngine()).run(
                new ReplaySession("WINV26", 0, 0, 8), source(samples), replayed::add);
        assertEquals(List.of(PriceReference.SMA9), replayed.stream().map(MovingAverageInteraction::reference).toList());
    }

    @Test void interactionStartedDuringWarmupIsConservativelyExcluded() throws Exception {
        List<MarketStructureSample> samples = samples(0, 50, 35, 20, 8, 2, -5, 4, 15, 25);
        List<MovingAverageInteraction> observed = new ArrayList<>();
        var result = new MarketStructureReplayRunner(new MarketStructureEngine()).run(
                new ReplaySession("WINV26", 0, 5, 8), source(samples), observed::add);
        assertEquals(0, observed.size());
        assertEquals(1, result.excludedWarmupInteractions());
        assertEquals(5, result.warmupSamples());
        assertEquals(4, result.analysisSamples());
    }

    private static List<MarketStructureSample> samples(long start, double... distances) {
        List<MarketStructureSample> result = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            result.add(new MarketStructureSample("WINV26", start + i,
                    188700 + distances[i], 190000, 188700));
        }
        return result;
    }

    private static MarketStructureSample sample(long time, double price, double sma21) {
        return new MarketStructureSample("WINV26", time, price, 190000, sma21);
    }

    private static ReplaySource<MarketStructureSample> source(List<MarketStructureSample> samples) {
        return consumer -> samples.forEach(consumer);
    }
}
