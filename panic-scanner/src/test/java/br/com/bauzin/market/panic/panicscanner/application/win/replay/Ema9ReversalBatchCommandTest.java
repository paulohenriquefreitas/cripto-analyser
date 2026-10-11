package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class Ema9ReversalBatchCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 8, 31);
    @TempDir Path temp;

    @Test
    void reportsConfirmedReversalsAndMonthTotalsWithoutCountingWarmupOrOpenFinalCandle() throws Exception {
        write(DAY, new double[]{100, 200, 250, 150, 100, 80, 200, 250, 300});
        write(DAY.plusDays(1), new double[]{100, 100, 100});
        var output = temp.resolve("report");
        var console = new ByteArrayOutputStream();
        assertThat(run(DAY, DAY.plusDays(1), output, console)).isZero();
        assertThat(Files.readString(output.resolve("daily.csv"))).contains(
                "2026-08-31,OK,8,1,1,2,9,", "2026-09-01,OK,2,0,0,0,3,");
        var events = Files.readAllLines(output.resolve("events.csv"));
        assertThat(events).hasSize(3);
        long start = DAY.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertThat(events.get(1)).startsWith(DAY + "," + (start + 5 * 300_000L) + "," + (start + 6 * 300_000L) + ",DOWN,");
        assertThat(events.get(2)).startsWith(DAY + "," + (start + 7 * 300_000L) + "," + (start + 8 * 300_000L) + ",UP,");
        assertThat(console.toString()).contains("2026-08", "2026-09", "PERIODO", "Situacao: COMPLETO");
        assertThat(Files.readString(output.resolve("report.txt"))).isEqualTo(console.toString());
        var manifest = JSON.readTree(output.resolve("run.json").toFile());
        assertThat(manifest.get("codeSha256").asText()).hasSize(64);
        assertThat(manifest.get("complete").asBoolean()).isTrue();
        assertThat(manifest.get("atrDisplacementFilter").asBoolean()).isFalse();
    }

    @Test
    void corruptDayDiscardsItsProvisionalEventsAndMissingDayIsReportedSeparately() throws Exception {
        Path file = write(DAY, new double[]{100, 200, 250, 150, 100, 80, 200, 250, 300});
        var metadata = JSON.readTree(file.resolveSibling(file.getFileName() + ".metadata.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).put("sha256", "invalid");
        JSON.writeValue(file.resolveSibling(file.getFileName() + ".metadata.json").toFile(), metadata);
        var output = temp.resolve("partial");
        var console = new ByteArrayOutputStream();
        assertThat(run(DAY, DAY.plusDays(1), output, console)).isEqualTo(2);
        assertThat(Files.readString(output.resolve("daily.csv"))).contains(
                "2026-08-31,FAILED,0,0,0,0,0,", "2026-09-01,MISSING,0,0,0,0,0,");
        assertThat(Files.readAllLines(output.resolve("events.csv"))).hasSize(1);
        assertThat(console.toString()).contains("Situacao: PARCIAL");
    }

    @Test
    void completeEmptyDatasetIsValidZeroAndRepeatingRunIsDeterministic() throws Exception {
        write(DAY, new double[]{});
        var first = temp.resolve("first");
        var second = temp.resolve("second");
        assertThat(run(DAY, DAY, first, new ByteArrayOutputStream())).isZero();
        assertThat(run(DAY, DAY, second, new ByteArrayOutputStream())).isZero();
        assertThat(Files.readString(first.resolve("daily.csv"))).contains("2026-08-31,OK,0,0,0,0,0,");
        for (String name : List.of("daily.csv", "events.csv")) {
            assertThat(Files.readString(first.resolve(name))).isEqualTo(Files.readString(second.resolve(name)));
        }
        assertThat(run(DAY, DAY, first, new ByteArrayOutputStream())).isEqualTo(1);
    }

    @Test
    void oldAtrOnArgumentIsRejectedBeforeCreatingReport() {
        var output = temp.resolve("forbidden-on");
        var errors = new ByteArrayOutputStream();
        assertThat(Ema9ReversalBatchCommand.execute(new String[]{"WINV26", DAY.toString(), DAY.toString(),
                temp.resolve("{symbol}-{date}.ndjson").toString(), output.toString(), "On"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors))).isEqualTo(1);
        assertThat(errors.toString()).contains("somente Off e aceito");
        assertThat(Files.exists(output)).isFalse();
    }

    private int run(LocalDate start, LocalDate end, Path output, ByteArrayOutputStream console) {
        return Ema9ReversalBatchCommand.execute(new String[]{"WINV26", start.toString(), end.toString(),
                temp.resolve("{symbol}-{date}.ndjson").toString(), output.toString()}, new PrintStream(console), new PrintStream(new ByteArrayOutputStream()));
    }

    private Path write(LocalDate date, double[] prices) throws Exception {
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var warmup = new ArrayList<Mt5Candle>();
        // Include actual warm-up turns; neither observations nor their reversals belong in daily counts.
        for (int i = 30; i > 0; i--) {
            double value = i > 15 ? (i % 4 < 2 ? 80 : 140) : 100;
            warmup.add(new Mt5Candle(start / 1000 - i * 300, value, value, value, value, 0, 0));
        }
        var content = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2, "symbol", "WINV26",
                "startMsc", start, "endMsc", start + 86_400_000L, "warmup", warmup, "official", List.of()))).append('\n');
        for (int i = 0; i < prices.length; i++) content.append(JSON.writeValueAsString(Map.of("type", "tick",
                "timeMsc", start + i * 300_000L, "sequence", i, "last", prices[i], "flags", 8, "volumeReal", 1))).append('\n');
        content.append(JSON.writeValueAsString(Map.of("type", "end", "rows", prices.length))).append('\n');
        Path file = temp.resolve("WINV26-" + date + ".ndjson");
        Files.writeString(file, content);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var metadata = new ReplayHistoryProvider.Metadata(date.toString(), "WINV26", "MT5", prices.length, prices.length,
                prices.length == 0 ? null : start, prices.length == 0 ? null : start + (prices.length - 1) * 300_000L,
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, "COMPLETE", sha);
        JSON.writeValue(file.resolveSibling(file.getFileName() + ".metadata.json").toFile(), metadata);
        return file;
    }
}
