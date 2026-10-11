package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider.Metadata;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LocalMlHistoryReaderTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.of(2026, 4, 6);
    private static final long START = DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    private Path fixture(String status) throws Exception {
        Path p = temp.resolve("WIN$N").resolve(DATE + ".ndjson"); Files.createDirectories(p.getParent());
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = 30; i > 0; i--) warmup.add(new Mt5Candle((START - i * 300_000L) / 1000, 100, 105, 95, 100, 0, 0));
        // Official includes future extremes; exporter must ignore it entirely.
        var s = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2, "symbol", "WIN$N",
                "startMsc", START, "endMsc", START + 86_400_000L, "warmup", warmup,
                "official", List.of(new Mt5Candle(START / 1000, 10000, 20000, 1, 10000, 0, 0))))).append('\n');
        for (int i = 0; i < 24; i++) s.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", START + i * 300_000L,
                "last", 100, "volumeReal", 1, "flags", 8, "sequence", i))).append('\n');
        s.append("{\"type\":\"end\",\"rows\":24}\n"); Files.writeString(p, s);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        JSON.writeValue(p.resolveSibling(p.getFileName() + ".metadata.json").toFile(), new Metadata(DATE.toString(), "WIN$N", "MT5",
                24, 24, START, START + 23 * 300_000L, DATE.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, status, hash));
        return p;
    }
    @Test void validLocalExportPreservesSchemaAndNeverUsesOfficialFutureBars() throws Exception {
        fixture("COMPLETE");
        Path output = temp.resolve("out");
        MlDatasetExportCommand.main(new String[]{"WIN$N", DATE.toString(), DATE.toString(), output.toString(), temp.toString()});
        var features = Files.readAllLines(output.resolve("features.csv"));
        var dataset = Files.readAllLines(output.resolve("dataset.csv"));
        assertThat(features).hasSize(25); assertThat(dataset).hasSize(2);
        assertThat(features.getFirst().split(",", -1)).hasSize(MlCandleDataset.featureColumns().size());
        assertThat(dataset.get(1).split(",", -1)).hasSize(MlCandleDataset.featureColumns().size() + MlCandleDataset.labelColumns().size());
        assertThat(features.get(1).split(",", -1)[9]).isEqualTo("100.0");
        assertThat(Files.readString(output.resolve("report.txt"))).contains("Valid examples: 1", "Days processed (including valid empty days): 1");
        assertThatIllegalArgumentException().isThrownBy(() -> MlDatasetExportCommand.main(new String[]{"WIN$N", DATE.toString(), DATE.toString(), output.toString(), temp.toString()}));
    }
    @Test void missingIncompleteAndTamperedLocalFilesAreRejected() throws Exception {
        var reader = new LocalMlHistoryReader();
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> reader.read(temp, "WIN$N", DATE, h -> {}, t -> {}));
        fixture("INCOMPLETE");
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> reader.read(temp, "WIN$N", DATE, h -> {}, t -> {}));
        Path p = fixture("COMPLETE"); Files.writeString(p, Files.readString(p).replace("\"last\":100", "\"last\":101"));
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> reader.read(temp, "WIN$N", DATE, h -> {}, t -> {}))
                .withMessageContaining("integrity");
    }
    @Test void truncationAndReorderedLastsAreRejected() throws Exception {
        Path p = fixture("COMPLETE"); Files.writeString(p, Files.readString(p).replace("{\"type\":\"end\",\"rows\":24}\n", ""));
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> new LocalMlHistoryReader().read(temp, "WIN$N", DATE, h -> {}, t -> {}));
        p = fixture("COMPLETE"); Files.writeString(p, Files.readString(p).replace("\"sequence\":1,", "\"sequence\":0,"));
        // ObjectMapper map order is not a contractual assumption: explicit invalid sequence replacement.
        String data = Files.readString(p).replace("\"sequence\":1}", "\"sequence\":0}"); Files.writeString(p, data);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> new LocalMlHistoryReader().read(temp, "WIN$N", DATE, h -> {}, t -> {}));
    }
    @Test void malformedImportTimestampIsAnInvalidDatasetNotAnUncaughtRuntimeError() throws Exception {
        Path p = fixture("COMPLETE"); Path manifest = p.resolveSibling(p.getFileName() + ".metadata.json");
        var node = JSON.readTree(manifest.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("importedAt", "invalid-date");
        JSON.writeValue(manifest.toFile(), node);
        assertThatExceptionOfType(java.io.IOException.class).isThrownBy(() -> new LocalMlHistoryReader().read(temp, "WIN$N", DATE, h -> {}, t -> {}))
                .withMessageContaining("timestamp");
    }
}
