package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class ContinuousWinDatasetTest {
    @TempDir Path temp;

    @Test void continuousDatasetIsSharedByCacheBatchAndVisualSession() throws Exception {
        var date = LocalDate.of(2026, 9, 23);
        var provider = new ReplayHistoryProvider(temp.resolve("{symbol}/{date}.ndjson").toString(),
                "MUST_NOT_RUN", "missing.py");
        var file = provider.resolve("WIN$N", date);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"type":"header","schema":2,"symbol":"WIN$N","startMsc":1790121600000,"endMsc":1790208000000,"warmup":[],"official":[]}
                {"type":"tick","timeMsc":1790121600001,"last":100,"flags":12,"volumeReal":1}
                {"type":"end","rows":1}
                """);
        assertThat(provider.ensureAvailable("WIN$N", date)).isEqualTo(file);
        assertThat(provider.metadata("WIN$N", date).contract()).isEqualTo("WIN$N");
        assertThat(new Setup91BatchRunner(temp.resolve("{symbol}/{date}.ndjson").toString())
                .run("WIN$N", date).lastsProcessed()).isEqualTo(1);
        var session = new M5ReplaySession(file, "WIN$N");
        session.close();
    }

    @Test void acceptingContinuousSymbolDoesNotAllowPathsOrOtherDollarSymbols() {
        var provider = new ReplayHistoryProvider(temp.resolve("{symbol}/{date}.ndjson").toString(), "unused", "unused");
        for (var symbol : new String[]{"../WIN$N", "WIN$N/other", "WIN$", "WIN$D"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> provider.resolve(symbol, LocalDate.of(2026, 9, 23)));
            assertThatIllegalArgumentException().isThrownBy(() -> Setup91BatchRunner.validateSymbol(symbol));
        }
    }
}
