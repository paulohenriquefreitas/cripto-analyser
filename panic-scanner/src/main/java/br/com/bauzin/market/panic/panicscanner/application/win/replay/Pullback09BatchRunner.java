package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5HistoricalPriceSource;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** One COMPLETE local day only. No importer, external process, Spring context or visual Replay. */
public final class Pullback09BatchRunner {
    public static final String DEFAULT_TEMPLATE = "historical-data/{symbol}/{date}.ndjson";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final String historyTemplate;

    public record Result(String symbol, LocalDate date, Path file, long lastsProcessed,
                         long elapsedNanos, List<Pullback09Setup.Event> events,
                         List<Pullback09ResearchRecord> records) {
        public Result {
            events = List.copyOf(events);
            records = List.copyOf(records);
        }
        public long count(Pullback09Setup.EventType type) {
            return events.stream().filter(event -> event.eventType() == type).count();
        }
        public long completeOutcomes() {
            return records.stream().filter(record -> record.outcome().complete5m()
                    && record.outcome().complete15m() && record.outcome().complete30m()).count();
        }
        public double elapsedSeconds() { return elapsedNanos / 1_000_000_000.0; }
        public double lastsPerSecond() { return elapsedNanos == 0 ? 0 : lastsProcessed / elapsedSeconds(); }
    }

    public Pullback09BatchRunner() { this(DEFAULT_TEMPLATE); }

    public Pullback09BatchRunner(String historyTemplate) {
        this.historyTemplate = Objects.requireNonNull(historyTemplate);
    }

    public Result run(String symbol, LocalDate date) throws IOException {
        long started = System.nanoTime();
        Objects.requireNonNull(date, "date");
        if (symbol == null || !(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}"))) {
            throw new IllegalArgumentException("Invalid symbol");
        }
        Path file = Path.of(historyTemplate.replace("{symbol}", symbol).replace("{date}", date.toString()));
        if (!Files.isRegularFile(file)) throw new IOException("Missing local dataset: " + file);
        Path manifest = file.resolveSibling(file.getFileName() + ".metadata.json");
        if (!Files.isRegularFile(manifest)) throw new IOException("Missing COMPLETE manifest: " + manifest);
        // Reuse only the persisted metadata record from stage 1, never its acquisition service.
        var metadata = JSON.readValue(manifest.toFile(), ReplayHistoryProvider.Metadata.class);
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        if (!"COMPLETE".equals(metadata.status())) throw new IOException("Dataset is not COMPLETE: " + file);
        if (!symbol.equals(metadata.contract()) || !date.toString().equals(metadata.date())
                || !"MT5".equals(metadata.origin()) || metadata.formatVersion() != 2
                || metadata.sha256() == null || metadata.records() < 0 || metadata.lasts() < 0
                || metadata.importedAt() == null || Instant.parse(metadata.importedAt()).toEpochMilli() < end) {
            throw new IOException("Invalid dataset metadata for " + symbol + " " + date);
        }

        var research = new Pullback09ResearchEngine();
        var processor = new Pullback09CausalProcessor(research);
        var events = new ArrayList<Pullback09Setup.Event>();
        var digest = sha256();
        Long[] times = {null, null};
        Mt5HistoricalPriceSource.Statistics statistics;
        try (var reader = new BufferedReader(new InputStreamReader(
                new DigestInputStream(Files.newInputStream(file), digest), StandardCharsets.UTF_8))) {
            statistics = new Mt5HistoricalPriceSource().streamHistorical(reader, header -> {
                if (!symbol.equals(header.symbol()) || header.startMsc() != start || header.endMsc() != end
                        || !Integer.valueOf(2).equals(header.schema())) {
                    throw new IllegalArgumentException("Dataset header does not match the requested UTC day/contract");
                }
                processor.warmUp(header);
            }, historical -> {
                if (times[0] == null) times[0] = historical.priceEvent().timeMsc();
                times[1] = historical.priceEvent().timeMsc();
                events.addAll(processor.accept(historical).events());
            });
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256())
                || statistics.eventsRead() != metadata.records() || statistics.lastEvents() != metadata.lasts()
                || !Objects.equals(times[0], metadata.firstTimeMsc()) || !Objects.equals(times[1], metadata.lastTimeMsc())
                || !metadata.equals(JSON.readValue(manifest.toFile(), ReplayHistoryProvider.Metadata.class))) {
            throw new IOException("Dataset integrity check failed for " + file);
        }
        research.finish();
        var records = research.researchRecords();
        return new Result(symbol, date, file, statistics.lastEvents(), System.nanoTime() - started, events, records);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
