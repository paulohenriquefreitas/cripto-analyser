package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider.Metadata;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Read-only local validation. Consumers must discard the entire day if validation fails. */
public final class LocalMlHistoryReader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public Metadata read(Path root, String symbol, LocalDate date,
                         Consumer<Mt5HistoricalPriceSource.Header> header,
                         Consumer<HistoricalCanonicalPriceEvent> tick) throws IOException {
        if (symbol == null || !(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}")))
            throw new IllegalArgumentException("Invalid symbol");
        Path file = root.resolve(symbol).resolve(date + ".ndjson");
        Path manifest = file.resolveSibling(file.getFileName() + ".metadata.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(manifest)) throw new IOException("Missing local dataset/manifest: " + file);
        Metadata m = JSON.readValue(manifest.toFile(), Metadata.class);
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), end = start + 86_400_000L;
        long importedAt;
        try { importedAt = m.importedAt() == null ? -1 : Instant.parse(m.importedAt()).toEpochMilli(); }
        catch (DateTimeException e) { throw new IOException("Invalid import timestamp: " + file, e); }
        if (!"COMPLETE".equals(m.status()) || !symbol.equals(m.contract()) || !date.toString().equals(m.date())
                || !"MT5".equals(m.origin()) || m.formatVersion() != 2 || m.records() < 0 || m.lasts() < 0
                || m.sha256() == null || importedAt < end)
            throw new IOException("Invalid COMPLETE metadata: " + file);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Long[] times = {null, null};
        Mt5HistoricalPriceSource.Statistics stats;
        try (var input = new BufferedReader(new InputStreamReader(new DigestInputStream(Files.newInputStream(file), digest), StandardCharsets.UTF_8))) {
            stats = new Mt5HistoricalPriceSource().streamHistorical(input, h -> {
                if (!symbol.equals(h.symbol()) || h.startMsc() != start || h.endMsc() != end || !Integer.valueOf(2).equals(h.schema()))
                    throw new IllegalArgumentException("Wrong day/symbol/schema");
                header.accept(h);
            }, t -> {
                if (times[0] == null) times[0] = t.priceEvent().timeMsc();
                times[1] = t.priceEvent().timeMsc(); tick.accept(t);
            });
        }
        if (!m.sha256().equals(HexFormat.of().formatHex(digest.digest())) || stats.eventsRead() != m.records()
                || stats.lastEvents() != m.lasts() || !Objects.equals(times[0], m.firstTimeMsc())
                || !Objects.equals(times[1], m.lastTimeMsc()) || !m.equals(JSON.readValue(manifest.toFile(), Metadata.class)))
            throw new IOException("Dataset integrity check failed: " + file);
        return m;
    }
}
