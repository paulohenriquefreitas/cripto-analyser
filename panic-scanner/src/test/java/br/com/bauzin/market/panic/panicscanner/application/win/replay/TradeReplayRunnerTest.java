package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplayFlowSnapshots;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TradeReplayRunnerTest {
    @Test void emptySequenceProducesExplicitEmptyStatistics() throws Exception {
        var result = run(session(0, 0, 10_000), List.of(), TradeReplayObserver.NONE);
        assertEquals(0, result.eventsRead());
        assertEquals(0, result.tradeStats().trades());
        assertTrue(result.tradeStats().firstPrice().isEmpty());
    }

    @Test void oneTradeIsProcessed() throws Exception {
        var result = run(session(0, 0, 10_000), List.of(trade(1, 100, 2, AggressorSide.BUY)), null);
        assertEquals(1, result.tradeStats().trades());
        assertEquals(100, result.tradeStats().firstPrice().orElseThrow());
    }

    @Test void buySellAndAmbiguousRemainDistinct() throws Exception {
        var result = run(session(0, 0, 10_000), List.of(
                trade(1, 100, 10, AggressorSide.BUY),
                trade(2, 99, 4, AggressorSide.SELL),
                trade(3, 101, 20, AggressorSide.AMBIGUOUS)), null);
        var stats = result.tradeStats();
        assertEquals(1, stats.buyTrades());
        assertEquals(1, stats.sellTrades());
        assertEquals(1, stats.ambiguousTrades());
        assertEquals(34, stats.totalVolume());
        assertEquals(6, stats.knownDelta());
    }

    @Test void sameTimestampAndIdenticalTradesAreAllPreservedInOrder() throws Exception {
        MarketTrade identical = trade(1_000, 100, 1, AggressorSide.BUY);
        List<MarketTrade> observed = new ArrayList<>();
        var result = run(session(0, 0, 2_000), List.of(identical, identical, identical),
                new TradeReplayObserver() { @Override public void onTrade(MarketTrade trade) { observed.add(trade); } });
        assertEquals(List.of(identical, identical, identical), observed);
        assertEquals(3, result.tradeStats().trades());
    }

    @Test void outOfOrderIsRejectedBeforeEventReachesObserver() {
        List<MarketTrade> observed = new ArrayList<>();
        var observer = new TradeReplayObserver() {
            @Override public void onTrade(MarketTrade trade) { observed.add(trade); }
        };
        assertThrows(IllegalArgumentException.class, () -> run(session(0, 0, 2_000), List.of(
                trade(2, 100, 1, AggressorSide.BUY), trade(1, 101, 1, AggressorSide.SELL)), observer));
        assertEquals(1, observed.size());
    }

    @Test void calculatesCompleteAggregateStatistics() throws Exception {
        var result = run(session(0, 0, 10), List.of(
                trade(1, 101, 2, AggressorSide.BUY),
                trade(2, 99, 3, AggressorSide.SELL),
                trade(3, 105, 4, AggressorSide.AMBIGUOUS)), null);
        var s = result.tradeStats();
        assertEquals(9, s.totalVolume());
        assertEquals(2, s.buyVolume());
        assertEquals(3, s.sellVolume());
        assertEquals(4, s.ambiguousVolume());
        assertEquals(-1, s.knownDelta());
        assertEquals(101, s.firstPrice().orElseThrow());
        assertEquals(105, s.lastPrice().orElseThrow());
        assertEquals(99, s.minPrice().orElseThrow());
        assertEquals(105, s.maxPrice().orElseThrow());
        assertEquals(1, s.firstTimeMsc().orElseThrow());
        assertEquals(3, s.lastTimeMsc().orElseThrow());
    }

    @Test void observerReceivesExactOneThreeFiveAndTenSecondSnapshots() throws Exception {
        List<MarketTrade> trades = List.of(
                trade(0, 100, 10, AggressorSide.BUY),
                trade(500, 101, 5, AggressorSide.SELL),
                trade(1_500, 102, 7, AggressorSide.BUY),
                trade(4_500, 103, 2, AggressorSide.SELL),
                trade(9_500, 104, 3, AggressorSide.BUY));
        List<ReplayFlowSnapshots> replayed = new ArrayList<>();
        run(session(0, 0, 10_000), trades, new TradeReplayObserver() {
            @Override public boolean observeFlowSnapshots() { return true; }
            @Override public void onFlowSnapshots(MarketTrade trade, ReplayFlowSnapshots snapshots) {
                replayed.add(snapshots);
            }
        });
        TradeFlowEngine direct = new TradeFlowEngine();
        List<ReplayFlowSnapshots> expected = new ArrayList<>();
        for (MarketTrade trade : trades) {
            direct.onTrade(trade);
            expected.add(snapshots(direct));
        }
        assertEquals(expected, replayed);
        ReplayFlowSnapshots last = replayed.getLast();
        assertEquals(1, last.oneSecond().tradeCount());
        assertEquals(1, last.threeSeconds().tradeCount());
        // Window semantics are (T-W,T], so the trade exactly at T-5s is excluded.
        assertEquals(1, last.fiveSeconds().tradeCount());
        assertEquals(5, last.tenSeconds().tradeCount());
    }

    @Test void finalReplayWindowsEqualDirectEngineForSameInput() throws Exception {
        List<MarketTrade> trades = List.of(
                trade(0, 100, 1, AggressorSide.BUY), trade(0, 100, 1, AggressorSide.BUY),
                trade(2_000, 99, 2, AggressorSide.SELL), trade(7_000, 101, 3, AggressorSide.AMBIGUOUS));
        TradeFlowEngine replayEngine = new TradeFlowEngine();
        new TradeReplayRunner(replayEngine).run(session(0, 0, 8_000), source(trades), null);
        TradeFlowEngine direct = new TradeFlowEngine();
        trades.forEach(direct::onTrade);
        assertEquals(snapshots(direct), snapshots(replayEngine));
    }

    @Test void runnerResetsEngineBetweenSessions() throws Exception {
        TradeFlowEngine engine = new TradeFlowEngine();
        TradeReplayRunner runner = new TradeReplayRunner(engine);
        runner.run(session(0, 0, 10), source(List.of(trade(1, 100, 5, AggressorSide.BUY))), null);
        runner.run(session(20, 20, 30), source(List.of(trade(21, 101, 2, AggressorSide.SELL))), null);
        assertEquals(1, engine.snapshot(TradeFlowEngine.TEN_SECONDS).tradeCount());
        assertEquals(-2, engine.snapshot(TradeFlowEngine.TEN_SECONDS).knownDelta());
    }

    @Test void warmupFeedsEngineButIsNotCountedOrObserved() throws Exception {
        List<MarketTrade> observed = new ArrayList<>();
        TradeFlowEngine engine = new TradeFlowEngine();
        var result = new TradeReplayRunner(engine).run(session(0, 5_000, 10_000), source(List.of(
                trade(4_500, 100, 10, AggressorSide.BUY),
                trade(5_000, 101, 3, AggressorSide.SELL))),
                new TradeReplayObserver() { @Override public void onTrade(MarketTrade t) { observed.add(t); } });
        assertEquals(1, result.warmupEvents());
        assertEquals(1, result.analysisEvents());
        assertEquals(1, observed.size());
        assertEquals(2, engine.snapshot(TradeFlowEngine.ONE_SECOND).tradeCount());
        assertEquals(-3, result.tradeStats().knownDelta());
    }

    @Test void analysisStartAndEndAreInclusive() throws Exception {
        var result = run(session(0, 10, 20), List.of(
                trade(9, 100, 1, AggressorSide.BUY),
                trade(10, 101, 1, AggressorSide.BUY),
                trade(20, 102, 1, AggressorSide.SELL)), null);
        assertEquals(1, result.warmupEvents());
        assertEquals(2, result.analysisEvents());
    }

    @Test void tradeOutsideDeclaredBoundsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(session(10, 10, 20),
                List.of(trade(9, 100, 1, AggressorSide.BUY)), null));
    }

    @Test void wallClockOnlyAffectsPerformanceMetadata() throws Exception {
        AtomicLong clock = new AtomicLong();
        TradeReplayRunner runner = new TradeReplayRunner(new TradeFlowEngine(),
                () -> clock.getAndAdd(1_000_000_000));
        var result = runner.run(session(0, 0, 10),
                source(List.of(trade(1, 100, 1, AggressorSide.BUY))), null);
        assertEquals(Duration.ofSeconds(1), result.wallClockDuration());
        assertEquals(1, result.eventsPerSecond());
    }

    private static br.com.bauzin.market.panic.panicscanner.domain.replay.ReplayRunResult run(
            ReplaySession session, List<MarketTrade> trades, TradeReplayObserver observer) throws Exception {
        return new TradeReplayRunner(new TradeFlowEngine()).run(session, source(trades), observer);
    }

    private static ReplaySource<MarketTrade> source(List<MarketTrade> trades) {
        return consumer -> trades.forEach(consumer);
    }

    private static ReplaySession session(long load, long analysis, long end) {
        return new ReplaySession("WINV26", load, analysis, end);
    }

    private static MarketTrade trade(long time, double price, double volume, AggressorSide side) {
        return new MarketTrade("WINV26", time, price, volume, side);
    }

    private static ReplayFlowSnapshots snapshots(TradeFlowEngine engine) {
        return new ReplayFlowSnapshots(snapshot(engine, TradeFlowEngine.ONE_SECOND),
                snapshot(engine, TradeFlowEngine.THREE_SECONDS),
                snapshot(engine, TradeFlowEngine.FIVE_SECONDS),
                snapshot(engine, TradeFlowEngine.TEN_SECONDS));
    }

    private static FlowSnapshot snapshot(TradeFlowEngine engine, Duration duration) {
        return engine.snapshot(duration);
    }
}
