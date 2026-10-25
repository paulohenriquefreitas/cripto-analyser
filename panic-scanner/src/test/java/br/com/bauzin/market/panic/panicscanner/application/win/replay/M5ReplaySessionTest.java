package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class M5ReplaySessionTest {
    @Test
    void structureReactionsReachJsonIndividuallyDespiteVisualThrottling(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("structure-wire.ndjson");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String original = historyWithTicks(new long[]{1, 2, 3}, new double[]{100, 135, 155});
        String[] lines = original.split("\\R");
        var header = mapper.readTree(lines[0]);
        ((com.fasterxml.jackson.databind.node.ObjectNode) header.get("warmup").get(12)).put("high", 110);
        ((com.fasterxml.jackson.databind.node.ObjectNode) header.get("warmup").get(17)).put("high", 130);
        StringBuilder text = new StringBuilder(mapper.writeValueAsString(header)).append('\n');
        for (int i = 1; i < lines.length; i++) {
            var row = mapper.readTree(lines[i]);
            if ("tick".equals(row.path("type").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) row).put("flags", 40).put("volumeReal", 400);
            }
            text.append(mapper.writeValueAsString(row)).append('\n');
        }
        Files.writeString(history, text);
        var session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        var frames = new java.util.concurrent.CopyOnWriteArrayList<M5ReplaySession.Snapshot>();
        var callbacks = new java.util.concurrent.CopyOnWriteArrayList<br.com.bauzin.market.panic.panicscanner.application.win.strategy.StructureReactionEvent>();
        var completed = new CountDownLatch(1);
        session.onStructureReaction(callbacks::add);
        session.addListener(frame -> {
            frames.add(frame);
            if ("COMPLETED".equals(frame.type())) completed.countDown();
        });
        try {
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
            var reactions = frames.stream().filter(f -> f.structureReaction() != null).toList();
            assertThat(reactions).hasSize(2);
            assertThat(reactions.stream().map(M5ReplaySession.Snapshot::structureReaction).toList())
                    .containsExactlyElementsOf(callbacks);
            assertThat(reactions).extracting(M5ReplaySession.Snapshot::timeMsc)
                    .containsExactly(6_000_002L, 6_000_003L);
            for (var frame : reactions) {
                assertThat(frame.type()).isEqualTo("MARKET_STATE");
                assertThat(frame.candle()).isNull();
                var json = mapper.readTree(mapper.writeValueAsString(frame));
                assertThat(json.path("timeMsc").asLong()).isEqualTo(frame.structureReaction().timeMsc());
                assertThat(json.path("structureReaction").path("reactionType").asText()).isEqualTo("BREAKOUT");
                assertThat(json.path("structureReaction").path("levelType").asText()).isEqualTo("PIVOT_TOP");
                assertThat(json.path("structureReaction").path("delta500ms").asLong()).isPositive();
            }
            assertThat(frames.stream().filter(f -> f.candle() != null).toList().getLast().candle().close()).isEqualTo(155);
        } finally { session.close(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void drainsEveryQueuedReactionOnceWithoutRetimestamping(@TempDir Path temp) throws Exception {
        var session = new M5ReplaySession(temp.resolve("unused"), "WINV26", Long.MAX_VALUE);
        var field = M5ReplaySession.class.getDeclaredField("pendingStructureReactions"); field.setAccessible(true);
        var queue = (java.util.Queue<br.com.bauzin.market.panic.panicscanner.application.win.strategy.StructureReactionEvent>) field.get(session);
        var first = new br.com.bauzin.market.panic.panicscanner.application.win.strategy.StructureReactionEvent(6000001, 110, "PIVOT_TOP", "TOUCH_REVERSAL", -400);
        var second = new br.com.bauzin.market.panic.panicscanner.application.win.strategy.StructureReactionEvent(6000002, 90, "PIVOT_BOTTOM", "BREAKOUT", -500);
        queue.add(first); queue.add(second);
        var frames = new ArrayList<M5ReplaySession.Snapshot>(); session.addListener(frames::add);
        var publish = M5ReplaySession.class.getDeclaredMethod("publishLatestMarketState", M5ReplaySession.Status.class);
        publish.setAccessible(true);
        publish.invoke(session, M5ReplaySession.Status.PAUSED);
        publish.invoke(session, M5ReplaySession.Status.COMPLETED);
        assertThat(queue).isEmpty();
        assertThat(frames).extracting(M5ReplaySession.Snapshot::structureReaction).containsExactly(first, second);
        assertThat(frames).extracting(M5ReplaySession.Snapshot::timeMsc).containsExactly(6000001L, 6000002L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeReleasesWorkerWaitingForClockOrResume(boolean paused, @TempDir Path temp) throws Exception {
        Path history = temp.resolve("close-waiting.ndjson");
        Files.writeString(history, historyWithTicks(new long[]{1, 60_001}, new double[]{100, 101}));
        var session = new M5ReplaySession(history, "WINV26");
        var first = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) {
                if (paused) session.pause();
                first.countDown();
            }
        });
        try {
            session.play();
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            // Inspect the actual worker without adding a production test API.
            var field = M5ReplaySession.class.getDeclaredField("worker");
            field.setAccessible(true);
            Thread worker = (Thread) field.get(session);
            assertThat(worker.isVirtual()).isTrue();
            Thread.State expected = paused ? Thread.State.WAITING : Thread.State.TIMED_WAITING;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.getState() != expected && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(worker.getState()).isEqualTo(expected);

            session.close();
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.CLOSED);
            assertThat(session.metrics().eventsProcessed()).isEqualTo(1);
            assertThat(session.currentTimeMsc()).isEqualTo(6_000_001L);
        } finally {
            session.close();
        }
    }

    @Test
    void speedChangeWhilePlayingReleasesClockWaitWithoutChangingEventTime(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("speed-waiting.ndjson");
        Files.writeString(history, historyWithTicks(new long[]{1, 60_001}, new double[]{100, 101}));
        var session = new M5ReplaySession(history, "WINV26");
        var first = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) first.countDown();
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });
        try {
            session.play();
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            session.setSpeed(100);
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.metrics().eventsProcessed()).isEqualTo(2);
            assertThat(session.currentTimeMsc()).isEqualTo(6_060_001L);
            assertThat(session.speed()).isEqualTo(100);
        } finally {
            session.close();
        }
    }

    @Test
    void timesAndTradesIncludesVolumeOnlyEventsWithoutChangingCandleSelection(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("flow-integration.ndjson");
        String text = historyWithTicks(new long[]{0, 1000, 2000}, new double[]{100, 101, 102})
                .replace("\"flags\":12", "\"flags\":40")
                .replace("{\"type\":\"tick\",\"timeMsc\":6002000",
                        "{\"type\":\"tick\",\"timeMsc\":6001500,\"last\":101,\"flags\":80,\"volumeReal\":5}\n{\"type\":\"tick\",\"timeMsc\":6002000")
                .replace("\"rows\":3", "\"rows\":4");
        Files.writeString(history, text);
        var session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        var states = new java.util.concurrent.CopyOnWriteArrayList<M5ReplaySession.Snapshot>();
        var completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if (snapshot.type().equals("MARKET_STATE")) states.add(snapshot);
            if (snapshot.type().equals("COMPLETED")) completed.countDown();
        });
        try {
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(session.metrics().eventsProcessed()).isEqualTo(3);
            var last = states.getLast();
            assertThat(last.candle().close()).isEqualTo(102);
            assertThat(last.flow().last()).isEqualTo(102);
            assertThat(last.flow().timeMsc()).isEqualTo(last.timeMsc());
            var five = last.flow().windows().getFirst();
            assertThat(five.events()).isEqualTo(4);
            assertThat(five.buyVolume()).isEqualTo(3);
            assertThat(five.sellVolume()).isEqualTo(5);
            assertThat(five.delta()).isEqualTo(-2);
            assertThat(five.unknownVolume()).isZero();
            // Four trades, including VOLUME-only, but only three candle events.
            // The sell at 6001500 expires exactly at the 500ms left boundary.
            assertThat(last.volumeDelta().processedTicks()).isEqualTo(4);
            assertThat(last.volumeDelta().delta()).isEqualTo(1);
            assertThat(last.volumeDelta().cumulativeDelta()).isEqualTo(-2);
            assertThat(last.flow().volumeDelta()).isEqualTo(last.volumeDelta());
            assertThat(session.volumeDelta()).isPresent();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(last));
            assertThat(json.path("volumeDelta").path("delta").asLong()).isEqualTo(1);
        } finally { session.close(); }
    }

    @Test
    void microFlowMatchesSharedLiveEngineAtAllSpeedsDespiteVisualCoalescing(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("micro-flow.ndjson");
        Files.writeString(history, historyWithTicks(new long[]{0, 100, 200}, new double[]{100, 101, 102})
                .replace("\"flags\":12", "\"flags\":40"));
        var live = new br.com.bauzin.market.panic.panicscanner.application.win.flow.MonitoringFlowEngine("WINV26");
        for (int i = 0; i < 3; i++) live.onTrade(new br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade(
                "WINV26", 6_000_000L+i*100, 100+i, 1,
                br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide.BUY), i);
        var expected = live.snapshot(6_000_200L).volumeDelta();
        for (double speed : new double[]{0, 1, 100}) {
            var session = speed == 0 ? new M5ReplaySession(history, "WINV26", Long.MAX_VALUE)
                    : new M5ReplaySession(history, "WINV26");
            if (speed > 0) session.setSpeed(speed);
            var completed = new CountDownLatch(1);
            var lastFlow = new java.util.concurrent.atomic.AtomicReference<M5ReplaySession.Snapshot>();
            session.addListener(s -> {
                if ("FLOW".equals(s.type())) lastFlow.set(s);
                if ("COMPLETED".equals(s.type())) completed.countDown();
            });
            try {
                session.play();
                assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(session.volumeDelta()).contains(expected);
                assertThat(lastFlow.get().volumeDelta()).isEqualTo(expected);
                assertThat(session.metrics().eventsProcessed()).isEqualTo(3);
                if (speed == 0) assertThat(session.metrics().coalescedMarketStates()).isGreaterThan(0);
            } finally { session.close(); }
        }
    }
    @Test
    void defaultsToRealTimeAndChangesSpeedWhilePaused(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("paced.ndjson");
        Files.writeString(history, historyWithTicks(new long[]{1, 2001}, new double[]{100, 101}));
        var session = new M5ReplaySession(history, "WINV26");
        var first = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) first.countDown();
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });
        try {
            assertThat(session.speed()).isEqualTo(1);
            session.play();
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.await(150, TimeUnit.MILLISECONDS)).isFalse();
            session.pause();
            session.setSpeed(100);
            assertThat(completed.await(100, TimeUnit.MILLISECONDS)).isFalse();
            session.play();
            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(session.metrics().eventsProcessed()).isEqualTo(2);
            assertThat(session.currentTimeMsc()).isEqualTo(6_002_001L);
            for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 101}) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> session.setSpeed(invalid))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        } finally {
            session.close();
        }
    }

    @Test
    void playPauseResumePreservesOrderCandleAndSma(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("history.ndjson");
        Files.writeString(history, historyText());
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", 50_000_000L);
        List<M5ReplaySession.Snapshot> snapshots = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            snapshots.add(snapshot);
            if ("MARKET_STATE".equals(snapshot.type()) && snapshots.stream()
                    .filter(item -> "MARKET_STATE".equals(item.type())).count() == 1) {
                session.pause();
            }
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        waitForStatus(session, M5ReplaySession.Status.PAUSED);
        assertThat(snapshots).filteredOn(s -> "MARKET_STATE".equals(s.type())).hasSize(1);

        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();

        List<M5ReplaySession.Snapshot> states = snapshots.stream()
                .filter(snapshot -> "MARKET_STATE".equals(snapshot.type())).toList();
        assertThat(states).hasSize(3);
        assertThat(states).extracting(M5ReplaySession.Snapshot::timeMsc)
                .containsExactly(6_000_001L, 6_000_002L, 6_300_001L);
        assertThat(states.get(0).candle().time()).isEqualTo(6_000L);
        assertThat(states.get(1).candle().time()).isEqualTo(6_000L);
        assertThat(states.get(1).candle().close()).isEqualTo(102.0);
        assertThat(states.get(2).candle().time()).isEqualTo(6_300L);
        assertThat(states).extracting(snapshot -> snapshot.candle().realVolume())
                .containsExactly(5L, 12L, 9L);
        assertThat(states.get(0).sma9()).isCloseTo(100.1111111111111,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(states.get(0).sma21()).isCloseTo(100.04761904761905,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(snapshots.stream().filter(s -> "MARKET_STATE".equals(s.type()))
                .allMatch(s -> s.timeMsc() <= session.currentTimeMsc())).isTrue();
        session.close();
    }

    @Test
    void streamsTheWholeSelectedTradingDayWithoutThirtyMinuteCutoff(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("full-day.ndjson");
        Files.writeString(history, fullDayHistoryText());
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", 50_000_000L);
        List<M5ReplaySession.Snapshot> states = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) states.add(snapshot);
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(states).extracting(M5ReplaySession.Snapshot::timeMsc)
                .containsExactly(9 * 3_600_000L + 1,
                        9 * 3_600_000L + 2, 14 * 3_600_000L + 1,
                        14 * 3_600_000L + 2);
        assertThat(states).extracting(snapshot -> snapshot.candle().time())
                .containsExactly(9 * 3_600L, 9 * 3_600L, 14 * 3_600L, 14 * 3_600L);
        assertThat(session.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
        assertThat(session.currentTimeMsc()).isEqualTo(14 * 3_600_000L + 2);
    }

    @Test
    void coalescesVisualStatesWithoutSkippingHistoricalEvents(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("coalesced.ndjson");
        Files.writeString(history, manyEventsHistoryText());
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.Snapshot> states = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) states.add(snapshot);
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(session.metrics().eventsProcessed()).isEqualTo(100);
        assertThat(session.metrics().marketStatesPublished()).isLessThan(10);
        assertThat(session.metrics().coalescedMarketStates()).isEqualTo(99);
        assertThat(states).last().extracting(M5ReplaySession.Snapshot::timeMsc)
                .isEqualTo(1_790_121_600_100L);
        assertThat(states).last().extracting(s -> s.candle().close()).isEqualTo(200.0);
        assertThat(states).last().extracting(s -> s.candle().realVolume()).isEqualTo(100L);
        assertThat(states).last().extracting(M5ReplaySession.Snapshot::vwap).isNotNull();
    }

    @Test
    void publishesFinalCoalescedCandleBeforeTheNextBucket(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("final-candle-before-next.ndjson");
        Files.writeString(history, historyWithTicks(
                new long[]{1, 2, 3, 300_001},
                new double[]{100, 110, 90, 95}));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.Snapshot> states = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) states.add(snapshot);
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(session.metrics().eventsProcessed()).isEqualTo(4);
        assertThat(states).extracting(M5ReplaySession.Snapshot::timeMsc)
                .containsExactly(6_000_001L, 6_000_003L, 6_300_001L);
        assertThat(states).extracting(snapshot -> snapshot.candle().time())
                .containsExactly(6_000L, 6_000L, 6_300L);
        M5ReplaySession.Candle completedCandle = states.get(1).candle();
        assertThat(List.of(completedCandle.open(), completedCandle.high(),
                completedCandle.low(), completedCandle.close()))
                .containsExactly(100.0, 110.0, 90.0, 90.0);
        assertThat(states.get(2).timeMsc()).isGreaterThan(states.get(1).timeMsc());
        assertThat(session.metrics().coalescedMarketStates()).isEqualTo(2);
        session.close();
    }

    @Test
    void publishesOneCausalLongOccurrenceDespiteCoalescingAndPausePlay(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("long-occurrence.ndjson");
        Files.writeString(history, historyWithTicks(
                new long[]{0, 1, 2, 3, 4, 4},
                new double[]{115, 112, 109, 102, 121, 130}));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.RuleOccurrenceMessage> occurrences = new ArrayList<>();
        List<M5ReplaySession.Snapshot> states = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type())) states.add(snapshot);
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });
        session.addRuleOccurrenceListener(occurrence -> {
            occurrences.add(occurrence);
            session.pause();
        });

        session.play();
        waitForStatus(session, M5ReplaySession.Status.PAUSED);
        assertThat(occurrences).hasSize(1);
        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(occurrences).containsExactly(new M5ReplaySession.RuleOccurrenceMessage(
                "RULE_OCCURRENCE", "SMA21_SAME_SIDE_MOVE_AWAY-0", "SMA21_SAME_SIDE_MOVE_AWAY",
                "WINV26", 6_000_004L, 121,
                br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection.LONG,
                br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference.SMA21,
                br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide.ABOVE,
                br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide.ABOVE));
        assertThat(session.metrics().eventsProcessed()).isEqualTo(6);
        assertThat(session.metrics().coalescedMarketStates()).isGreaterThan(0);
        assertThat(states).hasSizeLessThan(6);
        session.close();
    }

    @Test
    void publishesShortOccurrenceAndRejectsOppositeExitOrUntouchedInteraction(@TempDir Path temp) throws Exception {
        List<M5ReplaySession.RuleOccurrenceMessage> shortOccurrences = runRuleReplay(temp.resolve("short.ndjson"),
                new long[]{0, 1, 2, 3, 4, 5}, new double[]{85, 88, 91, 98, 79, 70});
        List<M5ReplaySession.RuleOccurrenceMessage> oppositeExit = runRuleReplay(temp.resolve("opposite.ndjson"),
                new long[]{0, 1, 2, 3, 4, 5}, new double[]{115, 112, 109, 102, 98, 79});
        List<M5ReplaySession.RuleOccurrenceMessage> untouched = runRuleReplay(temp.resolve("untouched.ndjson"),
                new long[]{0, 1, 2, 3, 4}, new double[]{115, 112, 109, 105, 121});

        assertThat(shortOccurrences).hasSize(1);
        assertThat(shortOccurrences.getFirst().direction())
                .isEqualTo(br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection.SHORT);
        assertThat(shortOccurrences.getFirst().approachSide())
                .isEqualTo(br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide.BELOW);
        assertThat(shortOccurrences.getFirst().exitSide())
                .isEqualTo(br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide.BELOW);
        assertThat(oppositeExit).isEmpty();
        assertThat(untouched).isEmpty();
    }

    @Test
    void occurrenceIdsAreDeterministicForTheSamePriceSequence(@TempDir Path temp) throws Exception {
        long[] offsets = {0, 1, 2, 3, 4, 4};
        double[] prices = {115, 112, 109, 102, 121, 130};
        List<M5ReplaySession.RuleOccurrenceMessage> first =
                runRuleReplay(temp.resolve("deterministic-1.ndjson"), offsets, prices);
        List<M5ReplaySession.RuleOccurrenceMessage> second =
                runRuleReplay(temp.resolve("deterministic-2.ndjson"), offsets, prices);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void pauseAtBucketPreservesResearchWithoutPublishingPullbackMarkers(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("pullb09-pause.ndjson");
        Files.writeString(history, pullback09HistoryText()
                .replace("\"endMsc\":6900000", "\"endMsc\":7200000")
                .replace("{\"type\":\"end\",\"rows\":8}",
                        "{\"type\":\"tick\",\"timeMsc\":6900001,\"last\":80,\"flags\":12,\"volumeReal\":1}\n"
                                + "{\"type\":\"end\",\"rows\":9}"));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.SetupEventMessage> setupEvents = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addSetupEventListener(setupEvents::add);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type()) && snapshot.timeMsc() == 6_300_001L
                    && session.status() == M5ReplaySession.Status.PLAYING) session.pause();
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        waitForStatus(session, M5ReplaySession.Status.PAUSED);

        assertThat(session.metrics().eventsProcessed()).isEqualTo(3);
        assertThat(session.currentTimeMsc()).isEqualTo(6_300_001L);
        assertThat(setupEvents).isEmpty();

        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(setupEvents).isEmpty();
        var research = session.researchRecords().getFirst();
        assertThat(research.snapshot().referencePrice()).isEqualTo(80);
        assertThat(research.snapshot().setupAvailableTimeMsc()).isEqualTo(6_900_001L);
        assertThat(research.snapshot().referenceBucket()).isEqualTo(6_900_000L);
        assertThat(research.snapshot().pullb09CandleTimeMsc()).isEqualTo(6_600_000L);
        assertThat(research.snapshot().candle3().close()).isEqualTo(112);
        assertThat(research.outcome().mfe5m()).isNull();
        assertThat(research.outcome().complete5m()).isFalse();
        assertThat(session.metrics().eventsProcessed()).isEqualTo(9);
        session.close();
    }

    private static List<M5ReplaySession.RuleOccurrenceMessage> runRuleReplay(
            Path history, long[] offsets, double[] prices) throws Exception {
        Files.writeString(history, historyWithTicks(offsets, prices));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.RuleOccurrenceMessage> occurrences = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addRuleOccurrenceListener(occurrences::add);
        session.addListener(snapshot -> {
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });
        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        session.close();
        return List.copyOf(occurrences);
    }

    @Test
    void neitherPullbackDirectionIsPublishedWhileResearchRemainsAvailable(@TempDir Path temp) throws Exception {
        long[] offsets = {1, 2, 300001, 300002, 300003, 600001, 600002, 600003,
                900001, 900002, 1200001, 1200002, 1200003, 1500001, 1500002, 1500003, 1800001};
        double[] prices = {90, 110, 110, 99, 105, 109, 111, 112,
                120, 90, 90, 101, 95, 91, 89, 88, 85};
        Path history = temp.resolve("both-directions.ndjson");
        Files.writeString(history, historyWithTicks(offsets, prices)
                .replace("\"endMsc\":6600000", "\"endMsc\":8100000"));
        var session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.SetupEventMessage> events = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addSetupEventListener(events::add);
        session.addListener(s -> { if ("COMPLETED".equals(s.type())) completed.countDown(); });
        try {
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            // The closed sell reversal drops 22 points; extremes no longer block Setup91.
            assertThat(events).extracting(M5ReplaySession.SetupEventMessage::setupType)
                    .containsExactly("SETUP91_SELL_ARMED", "SETUP91_SELL_CANCELLED");
            var records = session.researchRecords();
            assertThat(records).hasSize(2);
            var down = records.getLast().snapshot();
            assertThat(down.setupType()).isEqualTo(Pullback09Setup.EventType.PULLB09_DOWN);
            assertThat(down.referencePrice()).isEqualTo(85);
            assertThat(down.referenceBucket()).isEqualTo(7_800_000L);
            assertThat(down.crz09().close()).isEqualTo(90);
            assertThat(down.rj09().low()).isEqualTo(90);
            assertThat(down.candle3().close()).isEqualTo(88);
            assertThat(down.confirmationStrength()).isEqualTo(2);
            assertThat(session.researchCsv()).contains("PULLB09_DOWN");
        } finally { session.close(); }
    }

    private static String historyWithTicks(long[] offsets, double[] prices) {
        StringBuilder text = new StringBuilder();
        text.append("{\"type\":\"header\",\"schema\":2,\"symbol\":\"WINV26\",\"startMsc\":6000000,\"endMsc\":6600000,\"warmup\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) text.append(',');
            text.append("{\"time\":").append(i * 300)
                    .append(",\"open\":100,\"high\":100,\"low\":100,\"close\":100,\"tickVolume\":0,\"realVolume\":0}");
        }
        text.append("],\"official\":[]}\n");
        for (int i = 0; i < prices.length; i++) {
            text.append("{\"type\":\"tick\",\"timeMsc\":").append(6_000_000L + offsets[i])
                    .append(",\"last\":").append(prices[i]).append(",\"flags\":12,\"volumeReal\":1}\n");
        }
        text.append("{\"type\":\"end\",\"rows\":").append(prices.length).append("}\n");
        return text.toString();
    }

    private static void waitForStatus(M5ReplaySession session, M5ReplaySession.Status expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (session.status() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(session.status()).isEqualTo(expected);
    }

    private static String historyText() {
        StringBuilder text = new StringBuilder();
        text.append("{\"type\":\"header\",\"schema\":2,\"symbol\":\"WINV26\",\"startMsc\":6000000,\"endMsc\":6600000,\"warmup\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) text.append(',');
            long time = i * 300;
            text.append("{\"time\":").append(time)
                    .append(",\"open\":100,\"high\":100,\"low\":100,\"close\":100,\"tickVolume\":0,\"realVolume\":0}");
        }
        text.append("],\"official\":[]}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":6000001,\"last\":101,\"flags\":12,\"volumeReal\":5}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":6000002,\"last\":102,\"flags\":12,\"volumeReal\":7}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":6300001,\"last\":103,\"flags\":12,\"volumeReal\":9}\n");
        text.append("{\"type\":\"end\",\"rows\":3}\n");
        return text.toString();
    }

    private static String fullDayHistoryText() {
        StringBuilder text = new StringBuilder();
        long start = 9 * 3_600_000L;
        long end = 15 * 3_600_000L;
        text.append("{\"type\":\"header\",\"schema\":2,\"symbol\":\"WINV26\",\"startMsc\":")
                .append(start).append(",\"endMsc\":").append(end)
                .append(",\"warmup\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) text.append(',');
            text.append("{\"time\":").append(i * 300)
                    .append(",\"open\":100,\"high\":100,\"low\":100,\"close\":100,\"tickVolume\":0,\"realVolume\":0}");
        }
        text.append("],\"official\":[]}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":").append(9 * 3_600_000L + 1)
                .append(",\"last\":101,\"flags\":12,\"volumeReal\":5}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":").append(9 * 3_600_000L + 2)
                .append(",\"last\":102,\"flags\":12,\"volumeReal\":7}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":").append(14 * 3_600_000L + 1)
                .append(",\"last\":103,\"flags\":12,\"volumeReal\":9}\n");
        text.append("{\"type\":\"tick\",\"timeMsc\":").append(14 * 3_600_000L + 2)
                .append(",\"last\":104,\"flags\":12,\"volumeReal\":11}\n");
        text.append("{\"type\":\"end\",\"rows\":4}\n");
        return text.toString();
    }

    private static String manyEventsHistoryText() {
        StringBuilder text = new StringBuilder();
        long start = 1_790_121_600_000L;
        text.append("{\"type\":\"header\",\"schema\":2,\"symbol\":\"WINV26\",\"startMsc\":")
                .append(start).append(",\"endMsc\":").append(start + 600_000)
                .append(",\"warmup\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) text.append(',');
            text.append("{\"time\":").append(start / 1000 - (20 - i) * 300)
                    .append(",\"open\":100,\"high\":100,\"low\":100,\"close\":100,\"tickVolume\":0,\"realVolume\":0}");
        }
        text.append("],\"official\":[]}\n");
        for (int i = 1; i <= 100; i++) {
            text.append("{\"type\":\"tick\",\"timeMsc\":").append(start + i)
                    .append(",\"last\":").append(100 + i).append(",\"flags\":12,\"volumeReal\":1}\n");
        }
        text.append("{\"type\":\"end\",\"rows\":100}\n");
        return text.toString();
    }

    @Test
    void pullbackMarkersRemainAbsentAcrossThreeLaterM5Candles(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("pullb09-persistence.ndjson");
        String original = pullback09HistoryText();
        String laterCandles = """
                {"type":"tick","timeMsc":6900001,"last":113,"flags":12,"volumeReal":1}
                {"type":"tick","timeMsc":7200001,"last":114,"flags":12,"volumeReal":1}
                {"type":"tick","timeMsc":7500001,"last":115,"flags":12,"volumeReal":1}
                {"type":"tick","timeMsc":7800001,"last":116,"flags":12,"volumeReal":1}
                {"type":"end","rows":12}
                """;
        Files.writeString(history, original.replace("\"endMsc\":6900000", "\"endMsc\":8100000")
                .replace("{\"type\":\"end\",\"rows\":8}\n", laterCandles));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.SetupEventMessage> events = new ArrayList<>();
        List<List<M5ReplaySession.SetupEventMessage>> atLaterBuckets = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addSetupEventListener(events::add);
        session.addListener(snapshot -> {
            if ("MARKET_STATE".equals(snapshot.type()) && snapshot.candle().time() >= 7200) {
                atLaterBuckets.add(List.copyOf(events));
            }
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });
        try {
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(events).isEmpty();
            assertThat(atLaterBuckets).hasSize(3);
            atLaterBuckets.forEach(retained -> assertThat(retained).containsExactlyElementsOf(events));
            assertThat(session.metrics().eventsProcessed()).isEqualTo(12);
        } finally {
            session.close();
        }
    }

    private static String pullback09HistoryText() {
        StringBuilder text = new StringBuilder();
        text.append("{\"type\":\"header\",\"schema\":2,\"symbol\":\"WINV26\",\"startMsc\":6000000,\"endMsc\":6900000,\"warmup\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) text.append(',');
            text.append("{\"time\":").append(i * 300)
                    .append(",\"open\":100,\"high\":100,\"low\":100,\"close\":100,\"tickVolume\":0,\"realVolume\":0}");
        }
        text.append("],\"official\":[]}\n");
        long[] times = {6_000_001L, 6_000_002L, 6_300_001L, 6_300_002L,
                6_300_003L, 6_600_001L, 6_600_002L, 6_600_003L};
        double[] prices = {90, 110, 110, 99, 105, 109, 111, 112};
        for (int i = 0; i < times.length; i++) {
            text.append("{\"type\":\"tick\",\"timeMsc\":").append(times[i])
                    .append(",\"last\":").append(prices[i])
                    .append(",\"flags\":12,\"volumeReal\":1}\n");
        }
        text.append("{\"type\":\"end\",\"rows\":").append(times.length).append("}\n");
        return text.toString();
    }
}
