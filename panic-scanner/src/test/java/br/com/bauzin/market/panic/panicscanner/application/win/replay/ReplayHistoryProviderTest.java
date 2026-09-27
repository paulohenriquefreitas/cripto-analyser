package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayHistoryProviderTest {
    @Test
    void reusesOnlyACompleteCacheForTheRequestedUtcDay(@TempDir Path temp) throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 23);
        Path directory = temp.resolve("replay");
        Files.createDirectories(directory);
        Path file = directory.resolve("WINV26-2026-09-23.ndjson");
        Files.writeString(file, """
                {"type":"header","schema":2,"symbol":"WINV26","startMsc":1790121600000,"endMsc":1790208000000,"warmup":[],"official":[]}
                {"type":"tick","timeMsc":1790121600001,"last":100,"flags":12,"volumeReal":1}
                {"type":"end","rows":1}
                """);

        ReplayHistoryProvider provider = new ReplayHistoryProvider(
                temp.resolve("replay/{symbol}-{date}.ndjson").toString(),
                "executable-that-must-not-run",
                "missing-script.py");

        assertThat(provider.ensureAvailable("WINV26", date)).isEqualTo(file);
        assertThat(Files.readString(file)).contains("\"timeMsc\":1790121600001");
    }
}
