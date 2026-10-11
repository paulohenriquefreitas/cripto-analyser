package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Setup91ReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYMBOL = "WINV26";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final long START = DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    private static final long M5 = 300_000;
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"true,trigger", "false,trigger", "true,touch", "false,touch",
            "true,cancel", "false,cancel", "true,rollover", "false,rollover",
            "true,expire", "false,expire", "true,touch-expire", "false,touch-expire",
            "true,final", "false,final", "true,gap", "false,gap",
            "true,threepoint", "false,threepoint", "true,extremes-ignored", "false,extremes-ignored"})
    void replayMatchesBatchWithCausalSignalDetailsAndStrictBreakouts(boolean buy, String ending) throws Exception {
        Path file = fixture(buy, ending);
        byte[] original = Files.readAllBytes(file);
        byte[] manifest = Files.readAllBytes(manifest(file));
        var provider = new ReplayHistoryProvider(template(), "must-not-run-python", "missing.py") {
            @Override protected void runExporter(String symbol, LocalDate date, Path output) {
                throw new AssertionError("A valid COMPLETE local day must never invoke MT5/Python");
            }
        };
        assertThat(provider.ensureAvailable(SYMBOL, DAY)).isEqualTo(file);
        var batch = new Setup91BatchRunner(template()).run(SYMBOL, DAY);
        var session = new M5ReplaySession(file, SYMBOL, Long.MAX_VALUE);
        var events = new CopyOnWriteArrayList<M5ReplaySession.SetupEventMessage>();
        var states = new CopyOnWriteArrayList<M5ReplaySession.Snapshot>();
        var completed = new CountDownLatch(1);
        session.addSetupEventListener(events::add);
        session.addListener(s -> {
            if (s.type().equals("MARKET_STATE")) {
                states.add(s);
                if (s.timeMsc() < START + 2 * M5) assertThat(events).isEmpty();
            }
            if (s.type().equals("COMPLETED") || s.type().equals("ERROR")) completed.countDown();
        });
        try {
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
            assertThat(events).hasSize(ending.equals("touch") ? 1 : 2);
            assertThat(events).hasSameSizeAs(batch.events());
            for (int i = 0; i < events.size(); i++) {
                var actual = events.get(i);
                var expected = batch.events().get(i);
                assertThat(actual.setupType()).isEqualTo(expected.type().name());
                assertThat(actual.timeMsc()).isEqualTo(expected.timeMsc());
                assertThat(actual.sequence()).isEqualTo(expected.sequence());
                assertThat(actual.price()).isEqualTo(expected.lastPrice());
                assertThat(actual.candleTimeMsc()).isEqualTo(expected.signal().candle().bucketStartTimeMsc());
                assertThat(actual.signal()).isEqualTo(expected.signal());
                assertThat(actual.breakoutPrice()).isEqualTo(actual.setupType().endsWith("TRIGGERED") ? expected.lastPrice() : null);
            }
            var armed = events.getFirst();
            assertThat(armed.setupType()).isEqualTo(buy ? "SETUP91_BUY_ARMED" : "SETUP91_SELL_ARMED");
            assertThat(armed.candleTimeMsc()).isEqualTo(START + M5); // Reversal, never the breakout bucket.
            assertThat(armed.timeMsc()).isEqualTo(START + (ending.equals("gap") ? 3 : 2) * M5);
            assertThat(armed.signal().previousFiveDirection()).isEqualTo(ending.equals("threepoint")
                    ? Setup91.Direction.FLAT : buy ? Setup91.Direction.DOWN : Setup91.Direction.UP);
            assertThat(armed.signal().previousFiveEma9()).hasSize(5);
            var candle = armed.signal().candle();
            assertThat(candle.open()).isEqualTo(100);
            assertThat(candle.high()).isEqualTo(120);
            assertThat(candle.low()).isEqualTo(80);
            assertThat(candle.close()).isEqualTo(buy ? 110 : 90);
            if (ending.equals("trigger") || ending.equals("final") || ending.equals("threepoint")) {
                assertThat(events.getLast().sequence()).isEqualTo(16L); // seq 14 touches exactly: no event.
                assertThat(events.getLast().timeMsc()).isEqualTo(armed.timeMsc());
                assertThat(events.getLast().breakoutPrice()).isEqualTo(buy ? 121 : 79);
            }
            if (ending.equals("rollover")) {
                assertThat(events.getLast().sequence()).isEqualTo(armed.sequence());
                assertThat(events.getLast().setupType()).endsWith("TRIGGERED");
            }
            if (ending.equals("cancel") || ending.equals("expire") || ending.equals("touch-expire")) {
                assertThat(events.getLast().setupType()).endsWith("CANCELLED");
                assertThat(events.getLast().timeMsc()).isEqualTo(START + 3 * M5);
                // This LAST crosses the old extreme, but cancellation has already happened.
                assertThat(events.getLast().price()).isEqualTo(buy ? 130 : 70);
                assertThat(events.getLast().sequence()).isEqualTo(18L);
                assertThat(events).noneMatch(e -> e.setupType().endsWith("TRIGGERED"));
            }
            if (ending.equals("gap")) {
                assertThat(events.getLast().setupType()).endsWith("CANCELLED");
                assertThat(events.getLast().sequence()).isEqualTo(armed.sequence());
                assertThat(events.getLast().timeMsc()).isEqualTo(armed.timeMsc());
            }
            assertThat(states).anySatisfy(s -> {
                assertThat(s.closedEma9()).isNotNull();
                assertThat(s.closedEma9().time()).isEqualTo(armed.candleTimeMsc() / 1000);
                assertThat(s.closedEma9().value()).isEqualTo(armed.signal().ema9());
            });
            assertThat(states).allSatisfy(s -> {
                if (s.closedEma9() != null) assertThat(s.closedEma9().time()).isLessThan(s.candle().time());
            });
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
            assertThat(Files.readAllBytes(manifest(file))).isEqualTo(manifest);
            var wire = JSON.readTree(JSON.writeValueAsString(armed));
            assertThat(wire.path("signal").path("previousFiveEma9").size()).isEqualTo(5);
            assertThat(wire.path("signal").path("candle").path("bucketStartTimeMsc").asLong()).isEqualTo(START + M5);
        } finally { session.close(); }
    }

    @Test
    void pauseAfterArmingDoesNotConsumeFutureLastsAndResumeKeepsSequence() throws Exception {
        var session = new M5ReplaySession(fixture(true, "trigger"), SYMBOL, Long.MAX_VALUE);
        var armed = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        session.addSetupEventListener(e -> {
            if (!e.setupType().startsWith("SETUP91_")) return;
            events.add(e.setupType());
            if (e.setupType().endsWith("ARMED")) { session.pause(); armed.countDown(); }
        });
        session.addListener(s -> { if (s.type().equals("COMPLETED")) completed.countDown(); });
        try {
            session.play();
            assertThat(armed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.PAUSED);
            assertThat(events).containsExactly("SETUP91_BUY_ARMED");
            session.play();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsExactly("SETUP91_BUY_ARMED", "SETUP91_BUY_TRIGGERED");
        } finally { session.close(); }
    }

    private Path fixture(boolean buy, String ending) throws Exception {
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = 13; i > 0; i--) {
            double p = switch (i) { case 4 -> 106; case 3 -> 96; case 2 -> 94; case 1 -> 92; default -> 100; };
            if (ending.equals("threepoint") && i == 2) p = 114; // Mixed older EMA history, valid last two slopes.
            if (!buy) p = 200 - p;
            warmup.add(new Mt5Candle(START / 1000 - i * 300, p, p, p, p, 0, 0));
        }
        long[] offsets = {0, M5, M5 + 1, M5 + 1, M5 + 1, 2 * M5, 2 * M5, 2 * M5, 3 * M5, 3 * M5, 4 * M5};
        double[] prices = {90, 100, 120, 80, 110, 100, 120, 121, 130, 131, 132};
        int count = ending.equals("cancel") ? 9 : 8;
        if (ending.equals("cancel")) { prices[6] = 100.4; prices[7] = 100.4; }
        if (ending.equals("touch")) prices[7] = 120;
        if (ending.equals("expire")) { prices[6] = 110; prices[7] = 110; count = 11; }
        if (ending.equals("touch-expire")) { prices[7] = 120; count = 11; }
        if (ending.equals("final")) count = 11;
        if (ending.equals("gap")) { offsets[5] = 3 * M5; prices[5] = 121; count = 6; }
        if (ending.equals("rollover")) { prices[5] = 121; count = 6; }
        var text = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2, "symbol", SYMBOL,
                "startMsc", START, "endMsc", START + 86_400_000, "warmup", warmup, "official", List.of()))).append('\n');
        double priorExtreme = ending.equals("extremes-ignored") ? 92 : 110;
        text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", START,
                "sequence", 0, "last", buy ? priorExtreme : 200 - priorExtreme, "flags", 1336, "volumeReal", 1))).append('\n');
        text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", START,
                "sequence", 1, "last", 0, "flags", 2, "volumeReal", 0))).append('\n');
        for (int i = 0; i < count; i++) {
            text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", START + offsets[i],
                    "sequence", (i + 1) * 2, "last", buy ? prices[i] : 200 - prices[i], "flags", 1336, "volumeReal", 1))).append('\n');
        }
        text.append(JSON.writeValueAsString(Map.of("type", "end", "rows", count + 2))).append('\n');
        Path file = temp.resolve(SYMBOL).resolve(DAY + ".ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        JSON.writeValue(manifest(file).toFile(), new ReplayHistoryProvider.Metadata(DAY.toString(), SYMBOL, "MT5",
                count + 2, count + 1, START, START + offsets[count - 1],
                DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, "COMPLETE", hash));
        return file;
    }

    @Test
    void positionedReplaySuppressesOppositeSignalAndResumesOnlyAfterExit() throws Exception {
        Path file = fixture(true, "trigger");
        var rows = Files.readAllLines(file);
        var text = new StringBuilder();
        for (String row : rows) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(row);
            if (node.path("type").asText().equals("end")) continue;
            if (node.path("type").asText().equals("header")) {
                for (var candle : node.path("warmup")) {
                    var bar = (com.fasterxml.jackson.databind.node.ObjectNode) candle;
                    for (String key : List.of("open", "high", "low", "close")) bar.put(key, bar.path(key).asDouble() * 10);
                    bar.put("high", bar.path("close").asDouble() + 100);
                    bar.put("low", bar.path("close").asDouble() - 100);
                }
                var warmup = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("warmup");
                // ATR14 needs enough CLOSED bars before the signal, with a realistic 200-point range.
                for (int i = 14; i <= 15; i++) warmup.insert(0, JSON.valueToTree(
                        new Mt5Candle(START / 1000 - i * 300, 1000, 1100, 900, 1000, 0, 0)));
            } else node.put("last", node.path("last").asDouble() * 10);
            text.append(JSON.writeValueAsString(node)).append('\n');
        }
        long[] offsets = {2 * M5 + 1, 3 * M5, 3 * M5 + 1, 4 * M5, 5 * M5};
        double[] prices = {900, 890, 790, 1300, 1310};
        for (int i = 0; i < prices.length; i++) {
            text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", START + offsets[i],
                    "sequence", 17 + i, "last", prices[i], "flags", 1336, "volumeReal", 1))).append('\n');
        }
        text.append(JSON.writeValueAsString(Map.of("type", "end", "rows", 15))).append('\n');
        Files.writeString(file, text);
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        JSON.writeValue(manifest(file).toFile(), new ReplayHistoryProvider.Metadata(DAY.toString(), SYMBOL, "MT5",
                15, 14, START, START + 5 * M5, DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                2, "COMPLETE", hash));
        // Control: without execution state there IS a valid opposite breakout during the open position.
        assertThat(new Setup91BatchRunner(template()).run(SYMBOL, DAY).events())
                .anyMatch(e -> e.type() == Setup91Breakout.EventType.SETUP91_SELL_TRIGGERED);
        var session = new M5ReplaySession(file, SYMBOL, Long.MAX_VALUE);
        var setups = new CopyOnWriteArrayList<M5ReplaySession.SetupEventMessage>();
        var trades = new CopyOnWriteArrayList<Setup91TriggerResearch.TradeEvent>();
        var completed = new CountDownLatch(1);
        session.addSetupEventListener(setups::add);
        session.addTradeListener(trades::add);
        session.addListener(s -> { if (s.type().equals("COMPLETED") || s.type().equals("ERROR")) completed.countDown(); });
        try {
            session.play();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
            assertThat(setups).extracting(M5ReplaySession.SetupEventMessage::setupType)
                    .containsExactly("SETUP91_BUY_ARMED", "SETUP91_BUY_TRIGGERED", "SETUP91_BUY_ARMED", "SETUP91_BUY_TRIGGERED");
            assertThat(trades).extracting(Setup91TriggerResearch.TradeEvent::stage)
                    .containsExactly(Setup91TriggerResearch.Stage.ENTRY, Setup91TriggerResearch.Stage.EXIT,
                            Setup91TriggerResearch.Stage.REJECTED);
            assertThat(trades.getLast().timeMsc()).isGreaterThan(trades.get(1).timeMsc());
            assertThat(session.tradeResults()).containsExactlyElementsOf(new Setup91TriggerResearchRunner(template())
                    .run(SYMBOL, DAY, DAY, Setup91TriggerResearch.Mode.PARTIAL_1R_ATR_TRAILING).results());
        } finally { session.close(); }
    }

    private String template() { return temp.resolve("{symbol}/{date}.ndjson").toString(); }
    private static Path manifest(Path file) { return file.resolveSibling(file.getFileName() + ".metadata.json"); }
}
