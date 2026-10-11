package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Prepares closed UTC days on disk; never runs research or changes the dataset format. */
@Service
public final class HistoricalDatasetOrchestrator {
    public record Failure(LocalDate date, String message) {}
    public record Result(long requestedSessions, long cached, long imported, List<Failure> failures) {
        public Result { failures = List.copyOf(failures); }
        public long failed() { return failures.size(); }
    }

    private final ReplayHistoryProvider history;
    private final HistoricalDatasetImporter importer;

    public HistoricalDatasetOrchestrator(ReplayHistoryProvider history, HistoricalDatasetImporter importer) {
        this.history = Objects.requireNonNull(history);
        this.importer = Objects.requireNonNull(importer);
    }

    public Result prepare(String symbol, LocalDate start, LocalDate end) {
        importer.validateRange(symbol, start, end);
        long cached = 0;
        long imported = 0;
        var failures = new ArrayList<Failure>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Dataset preparation interrupted before " + date);
            }
            try {
                if (history.cached(symbol, date)) {
                    cached++;
                } else {
                    var result = importer.importRange(symbol, date, date, day -> {});
                    cached += result.reusedDays();
                    imported += result.importedDays();
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                Throwable cause = e;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                failures.add(new Failure(date, e.getMessage()
                        + (cause == e ? "" : ": " + cause.getMessage())));
            }
        }
        return new Result(ChronoUnit.DAYS.between(start, end) + 1, cached, imported, failures);
    }
}
