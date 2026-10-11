package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalDatasetOrchestratorTest {
    @TempDir Path temp;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    private static final String SYMBOL = "WINV26";

    private final class FakeMt5 extends ReplayHistoryProvider {
        final List<LocalDate> calls = new ArrayList<>();
        LocalDate failingDate;
        FakeMt5() { super(template(), "MUST_NOT_RUN", "missing.py"); }
        @Override protected void runExporter(String symbol, LocalDate date, Path output) throws IOException {
            calls.add(date);
            long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            Files.writeString(output, """
                    {"type":"header","schema":2,"symbol":"WINV26","startMsc":%d,"endMsc":%d,"warmup":[],"official":[]}
                    {"type":"tick","timeMsc":%d,"sequence":10,"last":100,"flags":1336,"volumeReal":5}
                    {"type":"tick","timeMsc":%d,"sequence":11,"last":110,"flags":1368,"volumeReal":7}
                    {"type":"end","rows":2}
                    """.formatted(start, start + 86_400_000, start + 1, start + 1));
            if (date.equals(failingDate)) throw new IOException("fixture import failure");
        }
    }

    private String template() { return temp.resolve("{symbol}/{date}.ndjson").toString(); }
    private HistoricalDatasetOrchestrator orchestrator(FakeMt5 history) {
        return new HistoricalDatasetOrchestrator(history, new HistoricalDatasetImporter(history));
    }
    private Path manifest(FakeMt5 history, LocalDate date) {
        Path file = history.resolve(SYMBOL, date);
        return file.resolveSibling(file.getFileName() + ".metadata.json");
    }

    @Test void fullCacheNeverCallsImporterOrRewritesCompleteFiles() throws Exception {
        var history = new FakeMt5();
        var prepare = orchestrator(history);
        prepare.prepare(SYMBOL, DAY, DAY.plusDays(2));
        var file = history.resolve(SYMBOL, DAY);
        var bytes = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        var metadata = Files.readString(manifest(history, DAY));
        history.calls.clear();
        var importer = spy(new HistoricalDatasetImporter(history));
        var result = new HistoricalDatasetOrchestrator(history, importer).prepare(SYMBOL, DAY, DAY.plusDays(2));
        assertThat(result).isEqualTo(new HistoricalDatasetOrchestrator.Result(3, 3, 0, List.of()));
        verify(importer, never()).importRange(any(), any(), any(), any());
        assertThat(history.calls).isEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(Files.readString(manifest(history, DAY))).isEqualTo(metadata);
    }

    @Test void partialCacheImportsOnlyMissingDayAndPublishesComplete() throws Exception {
        var history = new FakeMt5();
        history.ensureAvailable(SYMBOL, DAY);
        history.ensureAvailable(SYMBOL, DAY.plusDays(2));
        history.calls.clear();
        var result = orchestrator(history).prepare(SYMBOL, DAY, DAY.plusDays(2));
        assertThat(result).isEqualTo(new HistoricalDatasetOrchestrator.Result(3, 2, 1, List.of()));
        assertThat(history.calls).containsExactly(DAY.plusDays(1));
        assertThat(history.cached(SYMBOL, DAY.plusDays(1))).isTrue();
        assertThat(history.metadata(SYMBOL, DAY.plusDays(1)).status()).isEqualTo("COMPLETE");
    }

    @Test void incompleteIsImportedAgain() throws Exception {
        var history = new FakeMt5();
        history.ensureAvailable(SYMBOL, DAY);
        Path manifest = manifest(history, DAY);
        Files.writeString(manifest, Files.readString(manifest).replace("COMPLETE", "INCOMPLETE"));
        history.calls.clear();
        var result = orchestrator(history).prepare(SYMBOL, DAY, DAY);
        assertThat(result).isEqualTo(new HistoricalDatasetOrchestrator.Result(1, 0, 1, List.of()));
        assertThat(history.calls).containsExactly(DAY);
        assertThat(history.metadata(SYMBOL, DAY).status()).isEqualTo("COMPLETE");
    }

    @Test void failureRemainsIncompleteContinuesOtherDaysAndCanBeRetried() throws Exception {
        var history = new FakeMt5();
        history.failingDate = DAY;
        var prepare = orchestrator(history);
        var result = prepare.prepare(SYMBOL, DAY, DAY.plusDays(1));
        assertThat(result.requestedSessions()).isEqualTo(2);
        assertThat(result.cached()).isZero();
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures().getFirst().date()).isEqualTo(DAY);
        assertThat(result.failures().getFirst().message()).contains("fixture import failure");
        assertThat(history.metadata(SYMBOL, DAY).status()).isEqualTo("INCOMPLETE");
        assertThat(history.cached(SYMBOL, DAY)).isFalse();
        assertThat(Files.exists(history.resolve(SYMBOL, DAY))).isFalse();
        history.failingDate = null;
        history.calls.clear();
        assertThat(prepare.prepare(SYMBOL, DAY, DAY.plusDays(1)))
                .isEqualTo(new HistoricalDatasetOrchestrator.Result(2, 1, 1, List.of()));
        assertThat(history.calls).containsExactly(DAY);
    }

    @Test void corruptedCompleteIsReportedWithoutOverwriteOrImport() throws Exception {
        var history = new FakeMt5();
        var file = history.ensureAvailable(SYMBOL, DAY);
        Files.writeString(file, "corrupted");
        history.calls.clear();
        var result = orchestrator(history).prepare(SYMBOL, DAY, DAY);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.cached()).isZero();
        assertThat(result.imported()).isZero();
        assertThat(result.failures().getFirst().date()).isEqualTo(DAY);
        assertThat(history.calls).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("corrupted");
    }

    @Test void batchConsumesPreparedFileLocallyAndDoesNotAcquireMissingDays() throws Exception {
        var history = new FakeMt5();
        orchestrator(history).prepare(SYMBOL, DAY, DAY);
        history.calls.clear();
        history.failingDate = DAY;
        var runner = new Pullback09BatchRunner(template());
        assertThat(runner.run(SYMBOL, DAY).lastsProcessed()).isEqualTo(2);
        assertThatThrownBy(() -> runner.run(SYMBOL, DAY.plusDays(1)))
                .isInstanceOf(IOException.class).hasMessageContaining("Missing local dataset");
        assertThat(history.calls).isEmpty();
        assertThat(Files.exists(history.resolve(SYMBOL, DAY.plusDays(1)))).isFalse();
    }

    @Test void invalidRangeRejectedBeforeImport() {
        var history = new FakeMt5();
        var prepare = orchestrator(history);
        assertThatThrownBy(() -> prepare.prepare(SYMBOL, DAY.plusDays(1), DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prepare.prepare(SYMBOL, DAY, LocalDate.now(ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(history.calls).isEmpty();
    }

    @Test void interruptionStopsBeforeAnyImport() {
        var history = new FakeMt5();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> orchestrator(history).prepare(SYMBOL, DAY, DAY))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            assertThat(history.calls).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }
}
