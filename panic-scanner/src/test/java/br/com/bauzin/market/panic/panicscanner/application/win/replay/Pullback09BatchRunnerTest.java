package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class Pullback09BatchRunnerTest {
    @TempDir Path temp;
    static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"UP,true", "DOWN,true", "BOTH,true", "UP,false", "DOWN,false", "BOTH,false"})
    void matchesCausalEventsAndReplayResearchWithoutPublishingPullbackMarkers(String direction, boolean complete) throws Exception {
        Path file = fixture(direction, complete);
        var result = runner().run("WINV26", DAY);
        assertParity(file, result);
        assertThat(result.records()).hasSize(direction.equals("BOTH") ? 2 : 1);
        assertThat(result.records()).allMatch(r -> r.outcome().complete30m() == complete);
        assertThat(result.count(Pullback09Setup.EventType.PULLB09_UP)).isEqualTo(direction.equals("DOWN") ? 0 : 1);
        assertThat(result.count(Pullback09Setup.EventType.PULLB09_DOWN)).isEqualTo(direction.equals("UP") ? 0 : 1);
        if (complete && !direction.equals("BOTH")) {
            var outcome = result.records().getFirst().outcome();
            assertThat(List.of(outcome.mfe5m(), outcome.mfe15m(), outcome.mfe30m())).containsExactly(30.0, 40.0, 40.0);
            assertThat(List.of(outcome.mae5m(), outcome.mae15m(), outcome.mae30m())).containsExactly(15.0, 15.0, 20.0);
        }
    }

    @Test void sameTimestampSequenceVolumeAndFlagsRemainInPersistedOrder() throws Exception {
        Path file = fixture("UP", true);
        var events = new ArrayList<HistoricalCanonicalPriceEvent>();
        try (var reader = Files.newBufferedReader(file)) {
            new Mt5HistoricalPriceSource().streamHistorical(reader, h -> {}, events::add);
        }
        assertThat(events.get(0).priceEvent().timeMsc()).isEqualTo(events.get(1).priceEvent().timeMsc());
        assertThat(events.get(0).sequence()).isZero();
        assertThat(events.get(1).sequence()).isEqualTo(2); // Raw quote at sequence 1 was not a LAST.
        assertThat(events.get(0).priceEvent().price()).isEqualTo(90);
        assertThat(events.get(1).priceEvent().price()).isEqualTo(110);
        assertThat(events.get(0).volumeReal()).isEqualTo(1);
        assertThat(events.get(1).flags()).isEqualTo(1368);
        assertThat(runner().run("WINV26", DAY).lastsProcessed()).isEqualTo(events.size());
    }

    @Test void missingDayDoesNotImportOrCreateAnything() throws Exception {
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("Missing local dataset");
        try (var files = Files.list(temp)) { assertThat(files.count()).isZero(); }
    }

    @Test void missingManifestDoesNotAdoptOrImportTheFile() throws Exception {
        Path file = fixture("UP", true);
        Files.delete(manifest(file));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("Missing COMPLETE manifest");
        assertThat(Files.exists(manifest(file))).isFalse();
    }

    @Test void incompleteDayFailsWithoutModifyingDataset() throws Exception {
        Path file = fixture("UP", true);
        changeMetadata(file, "status", "INCOMPLETE");
        String original = Files.readString(file);
        String metadata = Files.readString(manifest(file));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("not COMPLETE");
        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(Files.readString(manifest(file))).isEqualTo(metadata);
    }

    @Test void hashMismatchAndTruncationNeverReturnAResult() throws Exception {
        Path file = fixture("UP", true);
        String original = Files.readString(file);
        Files.writeString(file, original.replace("\"volumeReal\":1", "\"volumeReal\":2"));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("integrity check failed");
        Files.writeString(file, original.substring(0, original.lastIndexOf("{\"type\":\"end\"")));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).isInstanceOf(EOFException.class);
    }

    @Test void metadataCountsAndContractMustMatch() throws Exception {
        Path file = fixture("UP", true);
        var node = (ObjectNode) JSON.readTree(Files.readString(manifest(file)));
        node.put("lasts", 999);
        JSON.writeValue(manifest(file).toFile(), node);
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("integrity check failed");
        changeMetadata(file, "contract", "WINZ26");
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("Invalid dataset metadata");
    }

    @Test void rejectsOutOfOrderSequenceRatherThanSortingIt() throws Exception {
        Path file = fixture("UP", true);
        Files.writeString(file, Files.readString(file).replace("\"sequence\":2,", "\"sequence\":0,"));
        changeMetadata(file, "sha256", hash(file));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("Unordered raw sequence");
    }

    @Test void rejectsHeaderCoveringMoreThanTheRequestedDay() throws Exception {
        Path file = fixture("UP", true);
        var lines = Files.readAllLines(file); // Small synthetic test fixture only.
        var header = (ObjectNode) JSON.readTree(lines.getFirst());
        header.put("endMsc", header.get("endMsc").longValue() + 86_400_000);
        lines.set(0, JSON.writeValueAsString(header));
        Files.write(file, lines);
        changeMetadata(file, "sha256", hash(file));
        assertThatThrownBy(() -> runner().run("WINV26", DAY)).hasMessageContaining("requested UTC day/contract");
    }

    @Test void consoleIncludesBothDirectionsWindowsAndIncompleteStatus() throws Exception {
        fixture("BOTH", false);
        var output = new ByteArrayOutputStream();
        Pullback09BatchCommand.print(runner().run("WINV26", DAY), new PrintStream(output));
        assertThat(output.toString()).contains("PULLB09 UP: 1", "PULLB09 DOWN: 1", "C3 bucket:",
                "Reference price:", "5m: INCOMPLETE", "15m: INCOMPLETE", "30m: INCOMPLETE", "LASTs/second:");
    }

    private Pullback09BatchRunner runner() {
        return new Pullback09BatchRunner(temp.resolve("{symbol}-{date}.ndjson").toString());
    }

    private Path fixture(String direction, boolean complete) throws Exception {
        long start = DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = 30; i > 0; i--) warmup.add(new Mt5Candle(start / 1000 - i * 300, 100, 100, 100, 100, 0, 0));
        var text = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2,
                "symbol", "WINV26", "startMsc", start, "endMsc", start + 86_400_000,
                "warmup", warmup, "official", List.of()))).append('\n');
        long[] initialOffsets = {1, 1, 300001, 300002, 300003, 600001, 600002, 600003, 900001,
                900002, 1200001, 1200002, 1200003, 1500001, 1500002, 1500003, 1800001};
        double[] initialPrices = {90, 110, 110, 99, 105, 109, 111, 112, 120, 90, 90, 101, 95, 91, 89, 88, 85};
        var times = new ArrayList<Long>();
        var prices = new ArrayList<Double>();
        int initialCount = direction.equals("BOTH") ? 17 : 9;
        for (int i = 0; i < initialCount; i++) { times.add(initialOffsets[i]); prices.add(initialPrices[i]); }
        if (complete) {
            long[] offsets = direction.equals("BOTH") ? new long[]{1800002, 2100001, 2700001, 3600001}
                    : new long[]{900002, 1200001, 1800001, 2700001};
            double[] values = direction.equals("BOTH") ? new double[]{70, 95, 55, 100} : new double[]{150, 105, 160, 100};
            for (int i = 0; i < offsets.length; i++) { times.add(offsets[i]); prices.add(values[i]); }
        }
        for (int i = 0; i < times.size(); i++) {
            long time = start + 9 * 3_600_000 + times.get(i);
            double price = direction.equals("DOWN") ? 200 - prices.get(i) : prices.get(i);
            text.append("{\"type\":\"tick\",\"timeMsc\":").append(time).append(",\"sequence\":").append(i * 2)
                    .append(",\"last\":").append(price).append(",\"flags\":").append(i % 2 == 0 ? 1336 : 1368)
                    .append(",\"volumeReal\":1}\n");
            if (i == 0) text.append("{\"type\":\"tick\",\"timeMsc\":").append(time)
                    .append(",\"sequence\":1,\"last\":0,\"flags\":2,\"volumeReal\":0}\n");
        }
        text.append("{\"type\":\"end\",\"rows\":").append(times.size() + 1).append("}\n");
        Path file = temp.resolve("WINV26-" + DAY + ".ndjson");
        Files.writeString(file, text);
        var metadata = new ReplayHistoryProvider.Metadata(DAY.toString(), "WINV26", "MT5", times.size() + 1,
                times.size(), start + 9 * 3_600_000 + times.getFirst(), start + 9 * 3_600_000 + times.getLast(),
                "2026-09-28T00:00:00Z", 2, "COMPLETE", hash(file));
        JSON.writeValue(manifest(file).toFile(), metadata);
        return file;
    }

    private static Path manifest(Path file) { return file.resolveSibling(file.getFileName() + ".metadata.json"); }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void changeMetadata(Path file, String field, String value) throws Exception {
        var node = (ObjectNode) JSON.readTree(Files.readString(manifest(file)));
        node.put(field, value);
        JSON.writeValue(manifest(file).toFile(), node);
    }

    static void assertParity(Path file, Pullback09BatchRunner.Result batch) throws Exception {
        var replay = new M5ReplaySession(file, batch.symbol());
        var events = new ArrayList<M5ReplaySession.SetupEventMessage>();
        var done = new CountDownLatch(1);
        replay.addSetupEventListener(events::add);
        replay.addListener(s -> { if (s.type().equals("COMPLETED") || s.type().equals("ERROR")) done.countDown(); });
        try {
            replay.play();
            assertThat(done.await(5, TimeUnit.MINUTES)).as("Replay completion").isTrue();
            assertThat(replay.status()).isEqualTo(M5ReplaySession.Status.COMPLETED);
            assertThat(batch.lastsProcessed()).isEqualTo(replay.metrics().eventsProcessed());
            assertThat(events).allSatisfy(e -> assertThat(e.setupType()).startsWith("SETUP91_"));
            // Batch still emits every Pullback09 event; visual Replay deliberately does not.
            var processor = new Pullback09CausalProcessor(new Pullback09ResearchEngine());
            var causalEvents = new ArrayList<Pullback09Setup.Event>();
            try (var reader = Files.newBufferedReader(file)) {
                new Mt5HistoricalPriceSource().streamHistorical(reader, processor::warmUp,
                        historical -> causalEvents.addAll(processor.accept(historical).events()));
            }
            assertThat(batch.events()).as("All CRZ09/RJ09/PULLB09 events in causal order")
                    .containsExactlyElementsOf(causalEvents);
            assertThat(batch.records()).as("All snapshots and 5/15/30m outcomes, field by field")
                    .containsExactlyElementsOf(replay.researchRecords());
        } finally { replay.close(); }
    }
}
