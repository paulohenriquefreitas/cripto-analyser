package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HistoricalDatasetImporterTest {
    @TempDir Path temp;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private final class FakeMt5 extends ReplayHistoryProvider {
        final List<LocalDate> downloads = new ArrayList<>();
        boolean fail;
        FakeMt5() {
            super(temp.resolve("dataset/{symbol}/{date}.ndjson").toString(), "must-not-run", "missing.py");
        }
        @Override protected void runExporter(String symbol, LocalDate date, Path output) throws IOException {
            downloads.add(date);
            Files.writeString(output, dataset(date));
            if (fail) throw new IOException("MT5 fixture failure");
        }
    }

    private static String dataset(LocalDate date) {
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return """
                {"type":"header","schema":2,"symbol":"WINV26","startMsc":%d,"endMsc":%d,"warmup":[{"time":%d,"open":90,"high":95,"low":85,"close":90,"tickVolume":3,"realVolume":4}],"official":[]}
                {"type":"tick","timeMsc":%d,"sequence":10,"last":100,"flags":1336,"volumeReal":5}
                {"type":"tick","timeMsc":%d,"sequence":11,"last":110,"flags":1368,"volumeReal":7}
                {"type":"end","rows":2}
                """.formatted(start, start + 86_400_000, start / 1000 - 300, start + 1, start + 1);
    }

    @Test void importsAMissingDayAndPersistsCompleteMetadata() throws Exception {
        var provider = new FakeMt5();
        var days = new ArrayList<HistoricalDatasetImporter.ImportedDay>();
        var result = new HistoricalDatasetImporter(provider).importRange("WINV26", DAY, DAY, days::add);
        assertThat(provider.downloads).containsExactly(DAY);
        assertThat(result).isEqualTo(new HistoricalDatasetImporter.Result(1, 0, 1));
        var metadata = days.getFirst().metadata();
        assertThat(metadata.status()).isEqualTo("COMPLETE");
        assertThat(metadata.origin()).isEqualTo("MT5");
        assertThat(metadata.contract()).isEqualTo("WINV26");
        assertThat(metadata.date()).isEqualTo(DAY.toString());
        assertThat(metadata.records()).isEqualTo(2);
        assertThat(metadata.lasts()).isEqualTo(2);
        assertThat(metadata.formatVersion()).isEqualTo(2);
        assertThat(metadata.sha256()).hasSize(64);
        assertThat(metadata.importedAt()).isNotBlank();
        assertThat(metadata.firstTimeMsc()).isEqualTo(metadata.lastTimeMsc());
        assertThat(Files.readString(days.getFirst().file())).isEqualTo(dataset(DAY));
    }

    @Test void reusesExistingCompleteDayWithoutDownloadingOrRewriting() throws Exception {
        var provider = new FakeMt5();
        var importer = new HistoricalDatasetImporter(provider);
        importer.importRange("WINV26", DAY, DAY, d -> {});
        Path file = provider.resolve("WINV26", DAY);
        var modified = Files.getLastModifiedTime(file);
        var metadata = provider.metadata("WINV26", DAY);
        provider.fail = true; // Any second MT5 request would fail this test.
        var result = importer.importRange("WINV26", DAY, DAY, d -> assertThat(d.reused()).isTrue());
        assertThat(result).isEqualTo(new HistoricalDatasetImporter.Result(1, 1, 0));
        assertThat(provider.downloads).containsExactly(DAY);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(provider.metadata("WINV26", DAY)).isEqualTo(metadata);
    }

    @Test void importsOnlyTheMissingDayInsideAPartiallyCachedRange() {
        var provider = new FakeMt5();
        var importer = new HistoricalDatasetImporter(provider);
        importer.importRange("WINV26", DAY, DAY, d -> {});
        importer.importRange("WINV26", DAY.plusDays(2), DAY.plusDays(2), d -> {});
        var result = importer.importRange("WINV26", DAY, DAY.plusDays(2), d -> {});
        assertThat(result).isEqualTo(new HistoricalDatasetImporter.Result(3, 2, 1));
        assertThat(provider.downloads).containsExactly(DAY, DAY.plusDays(2), DAY.plusDays(1));
    }

    @Test void truncatedFileAndIncompleteManifestAreNotCacheHits() throws Exception {
        var provider = new FakeMt5();
        Path file = provider.resolve("WINV26", DAY);
        Files.createDirectories(file.getParent());
        Files.writeString(file, dataset(DAY).replace("{\"type\":\"end\",\"rows\":2}", ""));
        assertThat(provider.cached("WINV26", DAY)).isFalse();
        provider.ensureAvailable("WINV26", DAY);
        Path manifest = file.resolveSibling(file.getFileName() + ".metadata.json");
        Files.writeString(manifest, Files.readString(manifest).replace("COMPLETE", "INCOMPLETE"));
        assertThat(provider.cached("WINV26", DAY)).isFalse();
        provider.ensureAvailable("WINV26", DAY);
        assertThat(provider.downloads).containsExactly(DAY, DAY);
        assertThat(provider.metadata("WINV26", DAY).status()).isEqualTo("COMPLETE");
    }

    @Test void preservesEqualTimestampsSequencePriceVolumeFlagsAndWarmup() throws Exception {
        var provider = new FakeMt5();
        Path file = provider.ensureAvailable("WINV26", DAY);
        var events = new ArrayList<HistoricalCanonicalPriceEvent>();
        try (var reader = Files.newBufferedReader(file)) {
            new Mt5HistoricalPriceSource().streamHistorical(reader,
                    header -> assertThat(header.warmup()).hasSize(1), events::add);
        }
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().priceEvent().timeMsc()).isEqualTo(events.getLast().priceEvent().timeMsc());
        assertThat(events).extracting(HistoricalCanonicalPriceEvent::sequence).containsExactly(10L, 11L);
        assertThat(events).extracting(e -> e.priceEvent().price()).containsExactly(100.0, 110.0);
        assertThat(events).extracting(HistoricalCanonicalPriceEvent::volumeReal).containsExactly(5.0, 7.0);
        assertThat(events).extracting(HistoricalCanonicalPriceEvent::flags).containsExactly(1336, 1368);
    }

    @Test void acceptsLegacySchema2WithoutSequenceOrManifestWithoutDownloading() throws Exception {
        var provider = new FakeMt5();
        Path file = provider.resolve("WINV26", DAY);
        Files.createDirectories(file.getParent());
        String original = dataset(DAY).replace("\"sequence\":10,", "").replace("\"sequence\":11,", "");
        Files.writeString(file, original);
        provider.fail = true;
        assertThat(provider.ensureAvailable("WINV26", DAY)).isEqualTo(file);
        assertThat(provider.downloads).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(original);
        var events = new ArrayList<HistoricalCanonicalPriceEvent>();
        new Mt5HistoricalPriceSource().streamHistorical(new StringReader(original), h -> {}, events::add);
        assertThat(events).extracting(HistoricalCanonicalPriceEvent::sequence).containsExactly(0L, 1L);
    }

    @Test void failedImportRemainsIncompleteAndIdentifiesItsDate() throws Exception {
        var provider = new FakeMt5();
        provider.fail = true;
        assertThatThrownBy(() -> new HistoricalDatasetImporter(provider).importRange("WINV26", DAY, DAY, d -> {}))
                .hasMessageContaining(DAY.toString());
        assertThat(provider.cached("WINV26", DAY)).isFalse();
        assertThat(provider.metadata("WINV26", DAY).status()).isEqualTo("INCOMPLETE");
        assertThat(Files.exists(provider.resolve("WINV26", DAY))).isFalse();
    }

    @Test void corruptCompleteFileIsNeverSilentlyOverwritten() throws Exception {
        var provider = new FakeMt5();
        Path file = provider.ensureAvailable("WINV26", DAY);
        Files.writeString(file, "corrupt");
        assertThatThrownBy(() -> provider.ensureAvailable("WINV26", DAY)).hasMessageContaining("Corrupted COMPLETE");
        assertThat(provider.downloads).containsExactly(DAY);
        assertThat(Files.readString(file)).isEqualTo("corrupt");
    }

    @Test void invalidIntervalsAndOpenUtcDaysFailBeforeImport() {
        var provider = new FakeMt5();
        var importer = new HistoricalDatasetImporter(provider);
        assertThatThrownBy(() -> importer.importRange("WINV26", DAY.plusDays(1), DAY, d -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.importRange("WINV26", DAY, LocalDate.now(ZoneOffset.UTC), d -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.downloads).isEmpty();
    }
}
