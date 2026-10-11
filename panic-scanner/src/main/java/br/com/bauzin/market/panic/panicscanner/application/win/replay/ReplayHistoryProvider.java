package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5HistoricalPriceSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

@Service
public class ReplayHistoryProvider {
    private final String historyTemplate;
    private final String pythonExecutable;
    private final Path historyScript;

    public ReplayHistoryProvider(
            @Value("${panic-scanner.replay.history-file}") String historyTemplate,
            @Value("${panic-scanner.replay.python:python}") String pythonExecutable,
            @Value("${panic-scanner.replay.history-script:panic-scanner/scripts/mt5_price_history.py}")
            String historyScript) {
        this.historyTemplate = Objects.requireNonNull(historyTemplate);
        this.pythonExecutable = Objects.requireNonNull(pythonExecutable);
        this.historyScript = Path.of(historyScript);
    }

    public record Metadata(String date, String contract, String origin, long records, long lasts,
                           Long firstTimeMsc, Long lastTimeMsc, String importedAt, int formatVersion,
                           String status, String sha256) {}
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public synchronized boolean cached(String symbol, LocalDate date) {
        Path target = resolve(symbol, date);
        if (!Files.exists(target)) {
            Path legacy = Path.of("panic-scanner/target/replay", symbol + "-" + date + ".ndjson");
            if (!legacy.toAbsolutePath().equals(target.toAbsolutePath()) && isValid(legacy, symbol, date)) {
                try {
                    Files.createDirectories(target.toAbsolutePath().getParent());
                    Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException e) { throw new IllegalStateException("Cannot preserve legacy dataset", e); }
            }
        }
        if (!Files.exists(target)) return false;
        Path manifest = manifest(target);
        try {
            if (Files.exists(manifest)) {
                Metadata metadata = JSON.readValue(manifest.toFile(), Metadata.class);
                if (!"COMPLETE".equals(metadata.status())) return false;
                if (java.time.Instant.parse(metadata.importedAt()).isBefore(date.plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant()))
                    throw new IllegalStateException("COMPLETE dataset was imported before the session closed: " + target);
                if (!symbol.equals(metadata.contract()) || !date.toString().equals(metadata.date())
                        || metadata.formatVersion() != 2 || !digest(target).equals(metadata.sha256()))
                    throw new IllegalStateException("Corrupted COMPLETE dataset: " + target);
                return true;
            }
            if (!isValid(target, symbol, date)) return false;
            writeMetadata(target, symbol, date);
            return "COMPLETE".equals(metadata(symbol, date).status());
        } catch (IOException e) { throw new IllegalStateException("Invalid dataset manifest: " + target, e); }
    }

    public synchronized Path ensureAvailable(String symbol, LocalDate date) {
        Path target = resolve(symbol, date);
        if (cached(symbol, date)) return target;
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
            try (var channel = java.nio.channels.FileChannel.open(lockPath,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                if (cached(symbol, date)) return target;
                return importDay(target, symbol, date);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not prepare replay history for " + symbol + " " + date, ex);
        }
    }

    private Path importDay(Path target, String symbol, LocalDate date) throws IOException {
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".part");
        try {
            var incomplete = new Metadata(date.toString(), symbol, "MT5", 0, 0, null, null,
                    java.time.Instant.now().toString(), 2, "INCOMPLETE", null);
            storeMetadata(target, incomplete);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Import cancelled");
            runExporter(symbol, date, temporary);
            if (!isValid(temporary, symbol, date))
                throw new IllegalStateException("MT5 export produced an invalid replay history");
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Import cancelled");
            moveAtomically(temporary, target);
            writeMetadata(target, symbol, date);
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    public Metadata metadata(String symbol, LocalDate date) throws IOException {
        return JSON.readValue(manifest(resolve(symbol, date)).toFile(), Metadata.class);
    }

    private static Path manifest(Path file) { return file.resolveSibling(file.getFileName() + ".metadata.json"); }

    private void writeMetadata(Path file, String symbol, LocalDate date) throws IOException {
        Long[] times = {null, null};
        Mt5HistoricalPriceSource.Statistics stats;
        try (var reader = Files.newBufferedReader(file)) {
            stats = new Mt5HistoricalPriceSource().streamHistorical(reader, h -> {}, event -> {
                if (times[0] == null) times[0] = event.priceEvent().timeMsc();
                times[1] = event.priceEvent().timeMsc();
            });
        }
        var metadata = new Metadata(date.toString(), symbol, "MT5", stats.eventsRead(), stats.lastEvents(),
                times[0], times[1], Files.getLastModifiedTime(file).toInstant().toString(), 2,
                !Files.getLastModifiedTime(file).toInstant().isBefore(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                        ? "COMPLETE" : "INCOMPLETE", digest(file));
        storeMetadata(file, metadata);
    }

    private void storeMetadata(Path file, Metadata metadata) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "metadata-", ".part");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), metadata);
            moveAtomically(temporary, manifest(file));
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String digest(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * The selected date is an UTC calendar day. The MT5 exporter receives the
     * half-open interval [00:00:00Z, 00:00:00Z of the next day); MT5 decides
     * which ticks actually exist in that interval. No broker-session hours or
     * fixed 30-minute window are imposed here.
     */
    public Path resolve(String symbol, LocalDate date) {
        if (symbol == null || !(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}"))) throw new IllegalArgumentException("Invalid contract");
        return Path.of(historyTemplate
                .replace("{symbol}", symbol)
                .replace("{date}", date.toString()));
    }

    private boolean isValid(Path file, String symbol, LocalDate date) {
        if (!Files.isRegularFile(file)) return false;
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            final boolean[] headerValid = {false};
            final boolean[] hasOfficialCandles = {false};
            var stats = new Mt5HistoricalPriceSource().streamHistorical(reader, header -> {
                hasOfficialCandles[0] = !header.official().isEmpty();
                headerValid[0] = symbol.equalsIgnoreCase(header.symbol())
                        && header.startMsc() == start
                        && header.endMsc() == end
                        && Integer.valueOf(2).equals(header.schema());
            }, event -> { });
            return headerValid[0] && (!hasOfficialCandles[0] || stats.lastEvents() > 0);
        } catch (Exception ex) {
            return false;
        }
    }

    protected void runExporter(String symbol, LocalDate date, Path output) throws IOException {
        long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Process process = new ProcessBuilder(
                pythonExecutable,
                historyScript.toString(),
                "--symbol", symbol,
                "--start-msc", Long.toString(start),
                "--end-msc", Long.toString(end),
                "--output", output.toString())
                .redirectErrorStream(true)
                .start();
        var processOutput = new java.io.ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) { stream.transferTo(processOutput); }
            catch (IOException ignored) { }
        });
        try {
            int exitCode = process.waitFor();
            drain.join();
            String outputText = processOutput.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (exitCode != 0) {
                throw new IllegalStateException("MT5 history export failed (" + exitCode + "): " + outputText);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while exporting MT5 history", ex);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
