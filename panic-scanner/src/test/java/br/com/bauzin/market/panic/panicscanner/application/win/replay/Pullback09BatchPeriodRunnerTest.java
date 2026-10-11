package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static br.com.bauzin.market.panic.panicscanner.application.win.replay.Pullback09Setup.EventType.*;

class Pullback09BatchPeriodRunnerTest {
    @TempDir Path temp;
    static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    static final String SYMBOL = "WINV26";
    String template() { return temp.resolve("{symbol}-{date}.ndjson").toString(); }

    @ParameterizedTest
    @CsvSource({"UP,UP,2,0", "DOWN,DOWN,0,2", "UP,DOWN,1,1"})
    void twoLocalDaysReuseDailyRunnerAndSumEveryEvent(String first, String second, long up, long down) throws Exception {
        fixture(DAY, first);
        fixture(DAY.plusDays(1), second);
        var daily = spy(new Pullback09BatchRunner(template()));
        var expectedFirst = new Pullback09BatchRunner(template()).run(SYMBOL, DAY);
        var expectedSecond = new Pullback09BatchRunner(template()).run(SYMBOL, DAY.plusDays(1));
        var result = new Pullback09BatchPeriodRunner(daily).run(SYMBOL, DAY, DAY.plusDays(1));
        var order = inOrder(daily);
        order.verify(daily).run(SYMBOL, DAY);
        order.verify(daily).run(SYMBOL, DAY.plusDays(1));
        verifyNoMoreInteractions(daily);
        assertThat(result.lasts()).isEqualTo(26);
        assertThat(result.sessions()).hasSize(2);
        assertThat(result.events().get(PULLB09_UP)).isEqualTo(up);
        assertThat(result.events().get(PULLB09_DOWN)).isEqualTo(down);
        for (var type : Pullback09Setup.EventType.values()) {
            assertThat(result.events().get(type)).isEqualTo(expectedFirst.count(type) + expectedSecond.count(type));
        }
        assertThat(result.complete()).isEqualTo(2);
        assertThat(result.incomplete()).isZero();
        assertThat(window(result, "TOTAL", 5).meanMfe()).isEqualTo(30);
        assertThat(window(result, "TOTAL", 30).meanMae()).isEqualTo(20);
        assertThat(result.lastsPerSecond()).isPositive();
    }

    @Test void thresholdsPercentagesMeansMediansAndWindowSpecificCompleteness() throws Exception {
        var daily = mock(Pullback09BatchRunner.class);
        var up = List.of(record(PULLB09_UP, 50, 10, true, true), record(PULLB09_UP, 100, 20, true, false),
                record(PULLB09_UP, 150, 30, false, false));
        var down = List.of(record(PULLB09_DOWN, 200, 40, true, true), record(PULLB09_DOWN, 300, 50, true, true),
                record(PULLB09_DOWN, 0, 60, true, true));
        when(daily.run(SYMBOL, DAY)).thenReturn(day(DAY, up));
        when(daily.run(SYMBOL, DAY.plusDays(1))).thenReturn(day(DAY.plusDays(1), down));
        var result = new Pullback09BatchPeriodRunner(daily).run(SYMBOL, DAY, DAY.plusDays(1));
        var all5 = window(result, "TOTAL", 5);
        assertThat(all5.complete()).isEqualTo(6);
        assertThat(all5.thresholds()).extracting(Pullback09BatchPeriodRunner.Threshold::reached).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(all5.thresholds().get(0).percentage()).isCloseTo(83.333333, within(0.000001));
        assertThat(all5.meanMfe()).isCloseTo(133.333333, within(0.000001));
        assertThat(all5.medianMfe()).isEqualTo(125);
        assertThat(all5.meanMae()).isEqualTo(35);
        assertThat(all5.medianMae()).isEqualTo(35);
        var up5 = window(result, "UP", 5);
        assertThat(up5.medianMfe()).isEqualTo(100);
        assertThat(up5.thresholds().get(2).percentage()).isCloseTo(33.333333, within(0.000001));
        var up15 = window(result, "UP", 15);
        assertThat(up15.complete()).isEqualTo(2);
        assertThat(up15.incomplete()).isEqualTo(1);
        assertThat(up15.meanMfe()).isEqualTo(75);
        assertThat(up15.medianMae()).isEqualTo(15);
        var up30 = window(result, "UP", 30);
        assertThat(up30.complete()).isEqualTo(1);
        assertThat(up30.incomplete()).isEqualTo(2);
        assertThat(up30.meanMfe()).isEqualTo(50);
        assertThat(up30.thresholds().getFirst().percentage()).isEqualTo(100);
        assertThat(window(result, "DOWN", 30).meanMfe()).isCloseTo(166.666667, within(0.000001));
        assertThat(window(result, "TOTAL", 30).complete()).isEqualTo(4);
        assertThat(result.complete()).isEqualTo(4);
        assertThat(result.incomplete()).isEqualTo(2);
        var output = new ByteArrayOutputStream();
        Pullback09BatchPeriodCommand.print(result, new PrintStream(output));
        assertThat(output.toString()).contains("PULLB09 TOTAL - 5 MIN", ">= 150 pts  3 / 6  50.00%",
                "150 points = 30 ticks", "MFE mean: 133.33; median: 125.00 pts", "PULLB09 DOWN - 30 MIN");
    }

    @Test void noCompleteOutcomesProduceNoMisleadingAveragesOrPercentages() throws Exception {
        var daily = mock(Pullback09BatchRunner.class);
        when(daily.run(SYMBOL, DAY)).thenReturn(day(DAY, List.of()));
        var result = new Pullback09BatchPeriodRunner(daily).run(SYMBOL, DAY, DAY);
        assertThat(result.windows()).allSatisfy(w -> {
            assertThat(w.complete()).isZero();
            assertThat(w.meanMfe()).isNull();
            assertThat(w.medianMae()).isNull();
        });
        var output = new ByteArrayOutputStream();
        Pullback09BatchPeriodCommand.print(result, new PrintStream(output));
        assertThat(output.toString()).contains("0 / 0  N/A", "MFE mean: N/A; median: N/A").doesNotContain("NaN");
    }

    @Test void missingDayFailsWithDateWithoutPrintingCompleteReportOrCreatingFiles() throws Exception {
        fixture(DAY, "UP");
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int code = Pullback09BatchPeriodCommand.execute(new String[]{SYMBOL, DAY.toString(), DAY.plusDays(1).toString(), template()},
                new PrintStream(output), new PrintStream(errors));
        assertThat(code).isEqualTo(1);
        assertThat(output.toString()).isEmpty();
        assertThat(errors.toString()).contains("FAILED WINV26 2026-09-15", "Missing local dataset");
        try (var files = Files.list(temp)) { assertThat(files.count()).isEqualTo(2); }
    }

    @Test void incompleteManifestFailsWithoutImportOrRewrite() throws Exception {
        fixture(DAY, "UP");
        Path metadata = temp.resolve(SYMBOL + "-" + DAY + ".ndjson.metadata.json");
        String incomplete = Files.readString(metadata).replace("COMPLETE", "INCOMPLETE");
        Files.writeString(metadata, incomplete);
        assertThatThrownBy(() -> new Pullback09BatchPeriodRunner(template()).run(SYMBOL, DAY, DAY))
                .hasMessageContaining("2026-09-14").hasMessageContaining("not COMPLETE");
        assertThat(Files.readString(metadata)).isEqualTo(incomplete);
    }

    @Test void invalidRangeDoesNotCallDailyRunner() {
        var daily = mock(Pullback09BatchRunner.class);
        assertThatThrownBy(() -> new Pullback09BatchPeriodRunner(daily).run(SYMBOL, DAY.plusDays(1), DAY))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(daily);
    }

    @Test void commandProcessesSmallLocalPeriod() throws Exception {
        fixture(DAY, "UP");
        fixture(DAY.plusDays(1), "DOWN");
        var output = new ByteArrayOutputStream();
        int code = Pullback09BatchPeriodCommand.execute(new String[]{SYMBOL, DAY.toString(), DAY.plusDays(1).toString(), template()},
                new PrintStream(output), System.err);
        assertThat(code).isZero();
        assertThat(output.toString()).contains("Sessions processed: 2", "LASTs: 26", "PULLB09_UP: 1", "PULLB09_DOWN: 1");
    }

    private static Pullback09BatchPeriodRunner.Window window(Pullback09BatchPeriodRunner.Result result, String direction, int minutes) {
        return result.windows().stream().filter(w -> w.direction().name().equals(direction) && w.minutes() == minutes).findFirst().orElseThrow();
    }
    private Pullback09BatchRunner.Result day(LocalDate date, List<Pullback09ResearchRecord> records) {
        return new Pullback09BatchRunner.Result(SYMBOL, date, temp, 10, 1, List.of(), records);
    }
    private static Pullback09ResearchRecord record(Pullback09Setup.EventType type, double mfe, double mae, boolean complete15, boolean complete30) {
        var snapshot = new Pullback09ResearchSnapshot("id", SYMBOL, 0, 0, 100, 0, null, null, null, 0,
                null, null, null, null, null, null, null, null, type, null);
        // Deliberately huge incomplete metrics verify that flags, not metric presence, control inclusion.
        var outcome = new Pullback09SetupOutcome("id", true, mfe, mae, complete15, complete15 ? mfe : 9999,
                complete15 ? mae : 9999, complete30, complete30 ? mfe : 9999, complete30 ? mae : 9999);
        return new Pullback09ResearchRecord(snapshot, outcome);
    }

    private void fixture(LocalDate date, String direction) throws Exception {
        var json = new ObjectMapper();
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var warmup = new ArrayList<Map<String, Number>>();
        for (int i = 30; i > 0; i--) warmup.add(Map.of("time", start / 1000 - i * 300, "open", 100,
                "high", 100, "low", 100, "close", 100, "tickVolume", 0, "realVolume", 0));
        var text = new StringBuilder(json.writeValueAsString(Map.of("type", "header", "schema", 2, "symbol", SYMBOL,
                "startMsc", start, "endMsc", start + 86_400_000, "warmup", warmup, "official", List.of()))).append('\n');
        long[] times = {1, 1, 300001, 300002, 300003, 600001, 600002, 600003, 900001, 900002, 1200001, 1800001, 2700001};
        double[] prices = {90, 110, 110, 99, 105, 109, 111, 112, 120, 150, 105, 160, 100};
        long base = start + 9 * 3_600_000;
        for (int i = 0; i < times.length; i++) text.append(json.writeValueAsString(Map.of("type", "tick",
                "timeMsc", base + times[i], "sequence", i, "last", direction.equals("DOWN") ? 200 - prices[i] : prices[i],
                "flags", 1336, "volumeReal", 1))).append('\n');
        text.append("{\"type\":\"end\",\"rows\":13}\n");
        Path file = temp.resolve(SYMBOL + "-" + date + ".ndjson");
        Files.writeString(file, text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var metadata = new ReplayHistoryProvider.Metadata(date.toString(), SYMBOL, "MT5", 13, 13, base + times[0],
                base + times[12], date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, "COMPLETE", hash);
        json.writeValue(temp.resolve(file.getFileName() + ".metadata.json").toFile(), metadata);
    }
}
