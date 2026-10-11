package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Consumer;

/** Imports reusable daily datasets only. Does not run indicators, setups or Replay. */
@Service
public final class HistoricalDatasetImporter {
    public record ImportedDay(LocalDate date, Path file, boolean reused,
                              ReplayHistoryProvider.Metadata metadata) {}
    public record Result(long requestedDays, long reusedDays, long importedDays) {}

    private final ReplayHistoryProvider history;

    public HistoricalDatasetImporter(ReplayHistoryProvider history) {
        this.history = Objects.requireNonNull(history);
    }

    public Result importRange(String symbol, LocalDate start, LocalDate end,
                              Consumer<ImportedDay> onDay) {
        validateRange(symbol, start, end);
        Objects.requireNonNull(onDay, "onDay");
        return importValidatedRange(symbol, start, end, onDay);
    }

    void validateRange(String symbol, LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Informe datas v?lidas: in?cio <= fim");
        }
        if (!end.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Somente dias UTC encerrados podem ser importados como COMPLETE");
        }
        history.resolve(symbol, start);
    }

    private Result importValidatedRange(String symbol, LocalDate start, LocalDate end,
                                        Consumer<ImportedDay> onDay) {
        long reused = 0;
        long imported = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Importa??o interrompida antes de " + date);
            }
            try {
                boolean cached = history.cached(symbol, date);
                Path file = cached ? history.resolve(symbol, date) : history.ensureAvailable(symbol, date);
                var metadata = history.metadata(symbol, date);
                if (!"COMPLETE".equals(metadata.status())) {
                    throw new IllegalStateException("Dataset n?o est? COMPLETE");
                }
                if (cached) reused++; else imported++;
                onDay.accept(new ImportedDay(date, file, cached, metadata));
            } catch (Exception e) {
                throw new IllegalStateException("Falha no hist?rico de " + symbol + " em " + date + ": " + e.getMessage(), e);
            }
        }
        return new Result(ChronoUnit.DAYS.between(start, end) + 1, reused, imported);
    }
}
