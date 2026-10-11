package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: only reads the already persisted 2026-09-14 dataset. Never imports. */
@EnabledIfSystemProperty(named = "batch.real.day", matches = "true")
class Pullback09BatchLocalDayTest {
    @Test void localSeptember14MatchesReplayExactly() throws Exception {
        Path repository = Path.of("..").toAbsolutePath().normalize();
        String template = repository.resolve("historical-data/{symbol}/{date}.ndjson").toString();
        var result = new Pullback09BatchRunner(template).run("WINV26", LocalDate.of(2026, 9, 14));
        assertThat(result.lastsProcessed()).isEqualTo(1_872_569);
        Pullback09BatchRunnerTest.assertParity(result.file(), result);
        var output = new ByteArrayOutputStream();
        try (var console = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            Pullback09BatchCommand.print(result, console);
            console.println("Batch x Replay: IDENTICAL (all events, snapshots and outcomes)");
            console.println("MT5/Python calls: ZERO (local-only runner)");
        }
        String text = output.toString(StandardCharsets.UTF_8);
        Files.writeString(Path.of("target/pullback09-batch-2026-09-14.txt"), text, StandardCharsets.UTF_8);
        System.out.print(text);
    }
}
