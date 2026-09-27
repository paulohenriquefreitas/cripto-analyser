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
public final class ReplayHistoryProvider {
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

    public Path ensureAvailable(String symbol, LocalDate date) {
        Path target = resolve(symbol, date);
        if (isValid(target, symbol, date)) return target;

        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                runExporter(symbol, date, temporary);
                if (!isValid(temporary, symbol, date)) {
                    throw new IllegalStateException("MT5 export produced an invalid replay history");
                }
                moveAtomically(temporary, target);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not prepare replay history for " + symbol + " " + date, ex);
        }
    }

    /**
     * The selected date is an UTC calendar day. The MT5 exporter receives the
     * half-open interval [00:00:00Z, 00:00:00Z of the next day); MT5 decides
     * which ticks actually exist in that interval. No broker-session hours or
     * fixed 30-minute window are imposed here.
     */
    Path resolve(String symbol, LocalDate date) {
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
            new Mt5HistoricalPriceSource().streamHistorical(reader, header -> {
                headerValid[0] = symbol.equalsIgnoreCase(header.symbol())
                        && header.startMsc() == start
                        && header.endMsc() == end
                        && Integer.valueOf(2).equals(header.schema());
            }, event -> { });
            return headerValid[0];
        } catch (Exception ex) {
            return false;
        }
    }

    private void runExporter(String symbol, LocalDate date, Path output) throws IOException {
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
        String outputText = new String(process.getInputStream().readAllBytes());
        try {
            int exitCode = process.waitFor();
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
