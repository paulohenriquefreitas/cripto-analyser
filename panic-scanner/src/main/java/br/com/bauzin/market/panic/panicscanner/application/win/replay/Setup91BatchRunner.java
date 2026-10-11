package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
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
import java.util.function.Consumer;

/** Local COMPLETE UTC-day Setup91 research. No acquisition, trades, outcomes or Replay session. */
public final class Setup91BatchRunner {
    public static final String DEFAULT_TEMPLATE = "historical-data/{symbol}/{date}.ndjson";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final String historyTemplate;

    public record Counts(long armed, long triggered, long cancelled) {
        /** Fraction, not percentage. Undefined when there are no armed setups. */
        public Double triggerRate() { return armed == 0 ? null : (double) triggered / armed; }
        public Counts plus(Counts other) {
            return new Counts(armed + other.armed, triggered + other.triggered, cancelled + other.cancelled);
        }
    }

    public record Summary(Counts buy, Counts sell) {
        public Counts total() { return buy.plus(sell); }
        public Summary plus(Summary other) { return new Summary(buy.plus(other.buy), sell.plus(other.sell)); }
        public static Summary empty() { return new Summary(new Counts(0, 0, 0), new Counts(0, 0, 0)); }
    }

    public record Result(String symbol, LocalDate date, Path file, long lastsProcessed,
                         List<Setup91Breakout.Event> events) {
        public Result { events = List.copyOf(events); }
        public Summary summary() {
            long buyArmed = 0, buyTriggered = 0, buyCancelled = 0;
            long sellArmed = 0, sellTriggered = 0, sellCancelled = 0;
            for (var event : events) {
                switch (event.type()) {
                    case SETUP91_BUY_ARMED -> buyArmed++;
                    case SETUP91_BUY_TRIGGERED -> buyTriggered++;
                    case SETUP91_BUY_CANCELLED -> buyCancelled++;
                    case SETUP91_SELL_ARMED -> sellArmed++;
                    case SETUP91_SELL_TRIGGERED -> sellTriggered++;
                    case SETUP91_SELL_CANCELLED -> sellCancelled++;
                }
            }
            return new Summary(new Counts(buyArmed, buyTriggered, buyCancelled),
                    new Counts(sellArmed, sellTriggered, sellCancelled));
        }
    }

    public Setup91BatchRunner() { this(DEFAULT_TEMPLATE); }
    public Setup91BatchRunner(String historyTemplate) { this.historyTemplate = Objects.requireNonNull(historyTemplate); }

    public Result run(String symbol, LocalDate date) throws IOException {
        return run(symbol, date, null);
    }

    /** Execution mode gates detection before evaluation, while sharing dataset validation. */
    Result run(String symbol, LocalDate date, Setup91TriggerResearch tracker) throws IOException {
        return run(symbol, date, tracker, null);
    }

    /** Observe closed EMA diagnostics, independently of entry/exit filters. Warm-up is not published. */
    Result observe(String symbol, LocalDate date, Consumer<Setup91.Observation> observer) throws IOException {
        return run(symbol, date, null, Objects.requireNonNull(observer));
    }

    Result observe(String symbol, LocalDate date, Consumer<Setup91.Observation> observer, boolean atrFilter) throws IOException {
        return run(symbol, date, null, Objects.requireNonNull(observer), atrFilter);
    }

    private Result run(String symbol, LocalDate date, Setup91TriggerResearch tracker,
                       Consumer<Setup91.Observation> observer) throws IOException {
        return run(symbol, date, tracker, observer, false);
    }

    private Result run(String symbol, LocalDate date, Setup91TriggerResearch tracker,
                       Consumer<Setup91.Observation> observer, boolean atrFilter) throws IOException {
        Objects.requireNonNull(date, "date");
        validateSymbol(symbol);
        Path file = Path.of(historyTemplate.replace("{symbol}", symbol).replace("{date}", date.toString()));
        if (!Files.isRegularFile(file)) throw new IOException("Missing local dataset: " + file);
        Path manifest = file.resolveSibling(file.getFileName() + ".metadata.json");
        if (!Files.isRegularFile(manifest)) throw new IOException("Missing COMPLETE manifest: " + manifest);
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

        var processor = new IntrabarM5Processor();
        var detector = new Setup91(Setup91.DEFAULT_RESEARCH_FLAT_SLOPE_THRESHOLD_POINTS, atrFilter);
        var breakout = new Setup91Breakout(detector);
        var events = new ArrayList<Setup91Breakout.Event>();
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
                var closed = header.warmup().stream().map(c -> new IntrabarCandleSnapshot(symbol,
                        c.time() * 1000L, c.open(), c.high(), c.low(), c.close())).toList();
                processor.warmUp(symbol, start, closed);
                if (tracker != null) tracker.warmUp(symbol, start, closed);
                for (var candle : closed) {
                    detector.onCandleClosed(candle, candle.bucketStartTimeMsc() + M5Bucket.DURATION_MSC);
                }
            }, historical -> {
                if (times[0] == null) times[0] = historical.priceEvent().timeMsc();
                times[1] = historical.priceEvent().timeMsc();
                boolean positionWasOpen = tracker != null && tracker.hasOpenPosition();
                if (tracker != null) tracker.onLast(historical);
                var update = breakout.onEvent(processor.onEvent(historical.priceEvent()), historical.sequence(),
                        positionWasOpen || (tracker != null && tracker.hasOpenPosition()));
                events.addAll(update.events());
                if (observer != null) update.observation().filter(o -> o.candle().bucketStartTimeMsc() >= start
                        && o.candle().bucketStartTimeMsc() < end).ifPresent(observer);
                if (tracker != null) {
                    update.events().stream().filter(e -> e.type().name().endsWith("_TRIGGERED"))
                            .forEach(tracker::onTrigger);
                }
            });
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(metadata.sha256())
                || statistics.eventsRead() != metadata.records() || statistics.lastEvents() != metadata.lasts()
                || !Objects.equals(times[0], metadata.firstTimeMsc()) || !Objects.equals(times[1], metadata.lastTimeMsc())
                || !metadata.equals(JSON.readValue(manifest.toFile(), ReplayHistoryProvider.Metadata.class))) {
            throw new IOException("Dataset integrity check failed for " + file);
        }
        // No synthetic end-of-day LAST, candle closure, cancellation or carry-over to another day.
        return new Result(symbol, date, file, statistics.lastEvents(), events);
    }

    static void validateSymbol(String symbol) {
        if (symbol == null || !(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}"))) throw new IllegalArgumentException("Invalid symbol");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
