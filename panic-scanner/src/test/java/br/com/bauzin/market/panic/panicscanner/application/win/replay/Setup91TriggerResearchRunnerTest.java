package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class Setup91TriggerResearchRunnerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final long START = DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tracksExactlyTheExistingBatchTriggersWithoutChangingDatasetOrSignals(boolean buy) throws Exception {
        Path file = fixture(buy);
        byte[] original = Files.readAllBytes(file);
        byte[] metadata = Files.readAllBytes(manifest(file));
        var batch = new Setup91BatchRunner(template()).run("WINV26", DAY);
        var triggers = batch.events().stream().filter(e -> e.type().name().endsWith("_TRIGGERED")).toList();
        assertThat(triggers).hasSize(1);
        var runner = new Setup91TriggerResearchRunner(template());
        var raw = runner.run("WINV26", DAY, DAY, Setup91TriggerResearch.Mode.FIRST_RETURN);
        assertThat(raw.results()).extracting(Setup91TriggerResearch.Result::trigger).containsExactlyElementsOf(triggers);
        var result = raw.results().getFirst();
        assertThat(result.exit()).isEqualTo(Setup91TriggerResearch.Exit.RETURN_TO_ENTRY);
        assertThat(result.risk()).isEqualTo(41);
        assertThat(result.mfe()).isEqualTo(19);
        assertThat(result.mae()).isZero();
        assertThat(result.exitSequence()).isEqualTo(10);
        assertThat(result.points()).isZero();
        var protectedModel = runner.run("WINV26", DAY, DAY, Setup91TriggerResearch.Mode.BREAKEVEN_1R_TARGET_2R);
        assertThat(protectedModel.results()).isEmpty(); // R=41 below minimum risk.
        assertThat(protectedModel.tradingDays()).isEqualTo(1);
        assertThat(Setup91TriggerResearchCommand.summary(protectedModel)).contains("Operacoes por dia com negocios: 0.0000");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(Files.readAllBytes(manifest(file))).isEqualTo(metadata);
        assertThat(new Setup91BatchRunner(template()).run("WINV26", DAY).events()).containsExactlyElementsOf(batch.events());
        assertThat(Setup91TriggerResearchCommand.csv(raw).lines().count()).isEqualTo(2);
        assertThat(Setup91TriggerResearchCommand.summary(raw)).contains("Total de triggers: 1", "STOP_LOSS: 0", "RETURN_TO_ENTRY: 1");
    }

    @Test void missingOrCorruptDatasetFailsWithoutAcquisition() throws Exception {
        var runner = new Setup91TriggerResearchRunner(template());
        assertThatThrownBy(() -> runner.run("WINV26", DAY, DAY, Setup91TriggerResearch.Mode.FIRST_RETURN))
                .isInstanceOf(java.io.IOException.class);
        Path file = fixture(true);
        Files.writeString(file, Files.readString(file).replace("\"volumeReal\":1", "\"volumeReal\":2"));
        assertThatThrownBy(() -> runner.run("WINV26", DAY, DAY, Setup91TriggerResearch.Mode.FIRST_RETURN))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("integrity");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void managedReplayUsesClosedM5WarmupAndAcceptsAdequateRisk(boolean buy) throws Exception {
        fixture(buy, 10);
        var report = new Setup91TriggerResearchRunner(template()).run(
                "WINV26", DAY, DAY, Setup91TriggerResearch.Mode.PARTIAL_1R_ATR_TRAILING);
        assertThat(report.results()).hasSize(1);
        var r = report.results().getFirst();
        assertThat(r.risk()).isEqualTo(410);
        assertThat(r.mfe()).isEqualTo(190);
        assertThat(r.exit()).isEqualTo(Setup91TriggerResearch.Exit.STOP_LOSS);
        assertThat(r.points()).isEqualTo(-410);
        assertThat(r.partialPrice()).isNull();
        var session = new M5ReplaySession(temp.resolve("WINV26").resolve(DAY + ".ndjson"), "WINV26", Long.MAX_VALUE);
        var events = new java.util.concurrent.CopyOnWriteArrayList<Setup91TriggerResearch.TradeEvent>();
        var done = new java.util.concurrent.CountDownLatch(1);
        session.addTradeListener(events::add);
        session.addListener(snapshot -> {
            if (snapshot.type().equals("COMPLETED") || snapshot.type().equals("ERROR")) done.countDown();
        });
        try {
            session.play();
            assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
            assertThat(session.tradeResults()).containsExactlyElementsOf(report.results());
            assertThat(events).extracting(Setup91TriggerResearch.TradeEvent::stage)
                    .containsExactly(Setup91TriggerResearch.Stage.ENTRY, Setup91TriggerResearch.Stage.EXIT);
            assertThat(events.getLast().points()).isEqualTo(r.points());
        } finally { session.close(); }

    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void managedReplayRejectsRiskAboveSafetyCapWithoutRemovingRawTrigger(boolean buy) throws Exception {
        fixture(buy, 20);
        var runner = new Setup91TriggerResearchRunner(template());
        var raw = runner.run("WINV26", DAY, DAY, Setup91TriggerResearch.Mode.FIRST_RETURN);
        assertThat(raw.results()).hasSize(1);
        assertThat(raw.results().getFirst().risk()).isEqualTo(820);
        for (var mode : List.of(Setup91TriggerResearch.Mode.PARTIAL_1R_ATR_TRAILING,
                Setup91TriggerResearch.Mode.BREAKEVEN_1R_TARGET_2R)) {
            assertThat(runner.run("WINV26", DAY, DAY, mode).results()).isEmpty();
        }
    }

    private Path fixture(boolean buy) throws Exception { return fixture(buy, 1); }

    private Path fixture(boolean buy, double scale) throws Exception {
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = scale > 1 ? 30 : 13; i > 0; i--) {
            double p = switch (i) { case 4 -> 106; case 3 -> 96; case 2 -> 94; case 1 -> 92; default -> 100; };
            if (!buy) p = 200 - p;
            warmup.add(new Mt5Candle(START / 1000 - i * 300, p * scale, (p + (scale > 1 ? 10 : 0)) * scale, (p - (scale > 1 ? 10 : 0)) * scale, p * scale, 0, 0));
        }
        double[] prices = {110, 90, 100, 120, 80, 110, 100, 120, 121, 140, 121, 80, 180};
        var text = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2,
                "symbol", "WINV26", "startMsc", START, "endMsc", START + 86_400_000,
                "warmup", warmup, "official", List.of()))).append('\n');
        for (int i = 0; i < prices.length; i++) {
            long time = START + (i < 2 ? 0 : i < 6 ? 300_000 : 600_000);
            text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", time, "sequence", i,
                    "last", (buy ? prices[i] : 200 - prices[i]) * scale, "flags", 1336, "volumeReal", 1))).append('\n');
        }
        text.append(JSON.writeValueAsString(Map.of("type", "end", "rows", prices.length))).append('\n');
        Path file = temp.resolve("WINV26").resolve(DAY + ".ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        JSON.writeValue(manifest(file).toFile(), new ReplayHistoryProvider.Metadata(DAY.toString(), "WINV26", "MT5",
                prices.length, prices.length, START, START + 600_000,
                DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, "COMPLETE", hash));
        return file;
    }

    private String template() { return temp.resolve("{symbol}/{date}.ndjson").toString(); }
    private Path manifest(Path file) { return file.resolveSibling(file.getFileName() + ".metadata.json"); }
}
