package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Inclusive UTC date range, independent daily setups; retains only daily counts across days. */
public final class Setup91BatchPeriodRunner {
    public record Session(LocalDate date, long lasts, Setup91BatchRunner.Summary summary) {}
    public record Result(String symbol, LocalDate start, LocalDate end, long lasts,
                         List<Session> sessions, Setup91BatchRunner.Summary summary) {
        public Result { sessions = List.copyOf(sessions); }
    }

    private final Setup91BatchRunner daily;
    public Setup91BatchPeriodRunner() { this(Setup91BatchRunner.DEFAULT_TEMPLATE); }
    public Setup91BatchPeriodRunner(String template) { daily = new Setup91BatchRunner(template); }

    public Result run(String symbol, LocalDate start, LocalDate end) throws IOException {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid period: start must be <= end");
        }
        Setup91BatchRunner.validateSymbol(symbol);
        var sessions = new ArrayList<Session>();
        var summary = Setup91BatchRunner.Summary.empty();
        long lasts = 0;
        for (LocalDate date = start;; date = date.plusDays(1)) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Period interrupted before " + date);
            try {
                var day = daily.run(symbol, date);
                var counts = day.summary();
                sessions.add(new Session(date, day.lastsProcessed(), counts));
                summary = summary.plus(counts);
                lasts += day.lastsProcessed();
            } catch (IOException | RuntimeException e) {
                throw new IOException("FAILED " + symbol + " " + date + ": " + e.getMessage()
                        + "; period report not produced", e);
            }
            if (date.equals(end)) break;
        }
        return new Result(symbol, start, end, lasts, sessions, summary);
    }
}
