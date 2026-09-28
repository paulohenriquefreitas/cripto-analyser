package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class M5ReplaySessionTest {
    @Test
    void playPauseResumePreservesOrderCandleAndSma(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("history.ndjson");
        Files.writeString(history, historyText());
        M5ReplaySession session = new M5ReplaySession(history, "WINV26");
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
        M5ReplaySession session = new M5ReplaySession(history, "WINV26");
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
    void pauseAtCrzDoesNotAdvanceSetupAndPlayContinuesWithNextLast(@TempDir Path temp) throws Exception {
        Path history = temp.resolve("pullb09-pause.ndjson");
        Files.writeString(history, pullback09HistoryText()
                .replace("\"endMsc\":6900000", "\"endMsc\":7200000")
                .replace("{\"type\":\"end\",\"rows\":8}",
                        "{\"type\":\"tick\",\"timeMsc\":6900001,\"last\":80,\"flags\":12,\"volumeReal\":1}\n"
                                + "{\"type\":\"end\",\"rows\":9}"));
        M5ReplaySession session = new M5ReplaySession(history, "WINV26", Long.MAX_VALUE);
        List<M5ReplaySession.SetupEventMessage> setupEvents = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        session.addSetupEventListener(setupEvent -> {
            setupEvents.add(setupEvent);
            if ("CRZ09_UP".equals(setupEvent.setupType())) session.pause();
        });
        session.addListener(snapshot -> {
            if ("COMPLETED".equals(snapshot.type())) completed.countDown();
        });

        session.play();
        waitForStatus(session, M5ReplaySession.Status.PAUSED);

        assertThat(session.metrics().eventsProcessed()).isEqualTo(3);
        assertThat(session.currentTimeMsc()).isEqualTo(6_300_001L);
        assertThat(setupEvents).extracting(M5ReplaySession.SetupEventMessage::setupType)
                .containsExactly("CRZ09_UP");
        assertThat(setupEvents.getFirst().timeMsc()).isEqualTo(6_300_001L);
        assertThat(setupEvents.getFirst().candleTimeMsc()).isEqualTo(6_000_000L);

        session.play();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(setupEvents).extracting(M5ReplaySession.SetupEventMessage::setupType)
                .containsExactly("CRZ09_UP", "RJ09_UP", "PULLB09_UP");
        assertThat(setupEvents.getLast().timeMsc()).isEqualTo(6_900_001L);
        assertThat(setupEvents.getLast().candleTimeMsc()).isEqualTo(6_600_000L);
        assertThat(setupEvents.getLast().price()).isEqualTo(112);
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
    void publishedSetupsRemainExactlyOnceAcrossThreeLaterM5Candles(@TempDir Path temp) throws Exception {
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
            assertThat(events).extracting(M5ReplaySession.SetupEventMessage::setupType)
                    .containsExactly("CRZ09_UP", "RJ09_UP", "PULLB09_UP");
            assertThat(events).extracting(M5ReplaySession.SetupEventMessage::eventId).doesNotHaveDuplicates();
            assertThat(events.getLast().candleTimeMsc()).isEqualTo(6_600_000L);
            assertThat(events.getLast().timeMsc()).isEqualTo(6_900_001L);
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
