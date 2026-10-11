package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

import static org.assertj.core.api.Assertions.*;

class Setup91BatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYMBOL = "WINV26";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final long M5 = 300_000;
    @TempDir Path temp;

    record Fixture(Path file, long start, List<Mt5Candle> warmup, List<HistoricalCanonicalPriceEvent> lasts) {}

    @ParameterizedTest
    @CsvSource({"true,trigger", "false,trigger", "true,cancel", "false,cancel", "true,pending", "false,pending"})
    void batchMatchesDirectEventsIncludingSignalValuesTimestampsAndSequences(boolean buy, String ending) throws Exception {
        var fixture = fixture(DAY, buy, ending);
        String before = Files.readString(fixture.file());
        String manifestBefore = Files.readString(manifest(fixture.file()));
        var batch = new Setup91BatchRunner(template()).run(SYMBOL, DAY);
        assertThat(batch.events()).containsExactlyElementsOf(direct(fixture));
        assertThat(batch.lastsProcessed()).isEqualTo(fixture.lasts().size());
        var expected = new Setup91BatchRunner.Counts(1, ending.equals("trigger") ? 1 : 0, ending.equals("cancel") ? 1 : 0);
        assertThat(buy ? batch.summary().buy() : batch.summary().sell()).isEqualTo(expected);
        assertThat(batch.summary().total()).isEqualTo(expected);
        assertThat(buy ? batch.summary().sell().triggerRate() : batch.summary().buy().triggerRate()).isNull();
        if (ending.equals("trigger")) {
            // ARMED, exact touch and breakout LASTs share a timestamp and retain source order.
            assertThat(batch.events().getLast().timeMsc()).isEqualTo(batch.events().getFirst().timeMsc());
            assertThat(batch.events().getLast().sequence()).isEqualTo(batch.events().getFirst().sequence() + 4);
        }
        assertThat(Files.readString(fixture.file())).isEqualTo(before);
        assertThat(Files.readString(manifest(fixture.file()))).isEqualTo(manifestBefore);
    }

    @Test
    void periodSumsDailyCountsAndComputesRatesFromTotals() throws Exception {
        fixture(DAY, true, "trigger");
        fixture(DAY.plusDays(1), false, "cancel");
        fixture(DAY.plusDays(2), true, "pending");
        var result = new Setup91BatchPeriodRunner(template()).run(SYMBOL, DAY, DAY.plusDays(2));
        assertThat(result.sessions()).hasSize(3);
        assertThat(result.summary().buy()).isEqualTo(new Setup91BatchRunner.Counts(2, 1, 0));
        assertThat(result.summary().sell()).isEqualTo(new Setup91BatchRunner.Counts(1, 0, 1));
        assertThat(result.summary().total()).isEqualTo(new Setup91BatchRunner.Counts(3, 1, 1));
        assertThat(result.summary().buy().triggerRate()).isEqualTo(0.5);
        assertThat(result.summary().total().triggerRate()).isCloseTo(1.0 / 3, within(1e-12));
        for (var session : result.sessions()) {
            assertThat(session.summary()).isEqualTo(new Setup91BatchRunner(template()).run(SYMBOL, session.date()).summary());
        }
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertThat(Setup91BatchPeriodCommand.execute(new String[]{SYMBOL, DAY.toString(), DAY.plusDays(2).toString(), template()},
                new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(err.toString()).isEmpty();
        assertThat(out.toString()).contains("Days processed: 3", "2026-09-01  BUY  1  1  0  100.00%",
                "2026-09-01  SELL  0  0  0  N/A", "2026-09-02  SELL  1  0  1  0.00%",
                "2026-09-03  TOTAL  1  0  0  0.00%", "PERIOD  BUY  2  1  0  50.00%",
                "PERIOD  SELL  1  0  1  0.00%", "PERIOD  TOTAL  3  1  1  33.33%");
    }

    @Test
    void warmupReversalDoesNotArmAndEmptyDayHasUndefinedRate() throws Exception {
        var base = fixture(DAY, true, "pending");
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = 15; i > 0; i--) {
            double price = switch (i) {
                case 6 -> 106; case 5 -> 96; case 4 -> 94; case 3 -> 92; case 2 -> 90; case 1 -> 110;
                default -> 100;
            };
            warmup.add(new Mt5Candle(base.start() / 1000 - i * 300, price, price, price, price, 0, 0));
        }
        var empty = write(DAY, warmup, List.of());
        var result = new Setup91BatchRunner(template()).run(SYMBOL, DAY);
        assertThat(result.events()).isEmpty();
        assertThat(result.events()).containsExactlyElementsOf(direct(empty));
        assertThat(result.lastsProcessed()).isZero();
        assertThat(result.summary().total().triggerRate()).isNull();
    }

    @Test
    void missingDayFailsWithoutPartialReportOrAcquisition() throws Exception {
        fixture(DAY, true, "pending");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertThat(Setup91BatchPeriodCommand.execute(new String[]{SYMBOL, DAY.toString(), DAY.plusDays(1).toString(), template()},
                new PrintStream(out), new PrintStream(err))).isEqualTo(1);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("FAILED WINV26 2026-09-02", "Missing local dataset");
        try (var files = Files.list(temp)) { assertThat(files.count()).isEqualTo(2); }
    }

    @Test
    void incompleteAndCorruptDatasetsAreRejectedWithoutRewriting() throws Exception {
        var fixture = fixture(DAY, true, "trigger");
        String complete = Files.readString(manifest(fixture.file()));
        String incomplete = complete.replace("COMPLETE", "INCOMPLETE");
        Files.writeString(manifest(fixture.file()), incomplete);
        assertThatThrownBy(() -> new Setup91BatchRunner(template()).run(SYMBOL, DAY)).hasMessageContaining("not COMPLETE");
        assertThat(Files.readString(manifest(fixture.file()))).isEqualTo(incomplete);
        Files.writeString(manifest(fixture.file()), complete);
        Files.writeString(fixture.file(), Files.readString(fixture.file()).replace("\"volumeReal\":1", "\"volumeReal\":2"));
        assertThatThrownBy(() -> new Setup91BatchRunner(template()).run(SYMBOL, DAY)).hasMessageContaining("integrity check failed");
    }

    @Test
    void invalidPeriodAndMissingManifestAreRejected() throws Exception {
        assertThatThrownBy(() -> new Setup91BatchPeriodRunner(template()).run(SYMBOL, DAY.plusDays(1), DAY))
                .isInstanceOf(IllegalArgumentException.class);
        var fixture = fixture(DAY, true, "pending");
        Files.delete(manifest(fixture.file()));
        assertThatThrownBy(() -> new Setup91BatchRunner(template()).run(SYMBOL, DAY)).hasMessageContaining("Missing COMPLETE manifest");
        assertThat(manifest(fixture.file())).doesNotExist();
    }

    private List<Setup91Breakout.Event> direct(Fixture fixture) {
        var processor = new IntrabarM5Processor();
        var detector = new Setup91();
        var candles = fixture.warmup().stream().map(c -> new IntrabarCandleSnapshot(SYMBOL,
                c.time() * 1000, c.open(), c.high(), c.low(), c.close())).toList();
        processor.warmUp(SYMBOL, fixture.start(), candles);
        for (var candle : candles) detector.onCandleClosed(candle, candle.bucketStartTimeMsc() + M5);
        var breakout = new Setup91Breakout(detector);
        var events = new ArrayList<Setup91Breakout.Event>();
        for (var last : fixture.lasts()) {
            events.addAll(breakout.onEvent(processor.onEvent(last.priceEvent()), last.sequence()).events());
        }
        return events;
    }

    private Fixture fixture(LocalDate date, boolean buy, String ending) throws Exception {
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var warmup = new ArrayList<Mt5Candle>();
        for (int i = 13; i > 0; i--) {
            double price = switch (i) { case 4 -> 106; case 3 -> 96; case 2 -> 94; case 1 -> 92; default -> 100; };
            if (!buy) price = 200 - price;
            warmup.add(new Mt5Candle(start / 1000 - i * 300, price, price, price, price, 0, 0));
        }
        long[] offsets = {0, M5, M5 + 1, M5 + 1, M5 + 1, 2 * M5, 2 * M5, 2 * M5, 3 * M5};
        double[] prices = {90, 100, 120, 80, 110, 100, 120, 121, 130};
        int size = ending.equals("trigger") ? 8 : ending.equals("cancel") ? 9 : 8;
        if (!ending.equals("trigger")) { prices[6] = 100.4; prices[7] = 100.4; }
        var lasts = new ArrayList<HistoricalCanonicalPriceEvent>();
        lasts.add(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent(SYMBOL, start, buy ? 110 : 90), 1, 1336, 0));
        for (int i = 0; i < size; i++) lasts.add(new HistoricalCanonicalPriceEvent(
                new CanonicalPriceEvent(SYMBOL, start + offsets[i], buy ? prices[i] : 200 - prices[i]), 1, 1336, (i + 1) * 2));
        return write(date, warmup, lasts);
    }

    private Fixture write(LocalDate date, List<Mt5Candle> warmup, List<HistoricalCanonicalPriceEvent> lasts) throws Exception {
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var text = new StringBuilder(JSON.writeValueAsString(Map.of("type", "header", "schema", 2, "symbol", SYMBOL,
                "startMsc", start, "endMsc", start + 86_400_000, "warmup", warmup, "official", List.of()))).append('\n');
        for (int i = 0; i < lasts.size(); i++) {
            var last = lasts.get(i);
            text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", last.priceEvent().timeMsc(),
                    "sequence", last.sequence(), "last", last.priceEvent().price(), "flags", last.flags(), "volumeReal", 1))).append('\n');
            if (i == 0) text.append(JSON.writeValueAsString(Map.of("type", "tick", "timeMsc", last.priceEvent().timeMsc(),
                    "sequence", 1, "last", 0, "flags", 2, "volumeReal", 0))).append('\n');
        }
        int rows = lasts.isEmpty() ? 0 : lasts.size() + 1;
        text.append(JSON.writeValueAsString(Map.of("type", "end", "rows", rows))).append('\n');
        Path file = temp.resolve(SYMBOL + "-" + date + ".ndjson");
        Files.writeString(file, text);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var metadata = new ReplayHistoryProvider.Metadata(date.toString(), SYMBOL, "MT5", rows, lasts.size(),
                lasts.isEmpty() ? null : lasts.getFirst().priceEvent().timeMsc(),
                lasts.isEmpty() ? null : lasts.getLast().priceEvent().timeMsc(),
                date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(), 2, "COMPLETE", hash);
        JSON.writeValue(manifest(file).toFile(), metadata);
        return new Fixture(file, start, warmup, lasts);
    }

    private String template() { return temp.resolve("{symbol}-{date}.ndjson").toString(); }
    private static Path manifest(Path file) { return file.resolveSibling(file.getFileName() + ".metadata.json"); }
}
