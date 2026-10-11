package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Single-pass execution with Batch dataset validation and position-gated signal detection. */
public final class Setup91TriggerResearchRunner {
    public record Report(String symbol, LocalDate start, LocalDate end, Setup91TriggerResearch.Mode mode,
                         List<Setup91TriggerResearch.Result> results, int tradingDays) {
        public Report { results = List.copyOf(results); }
        public Report(String symbol, LocalDate start, LocalDate end, Setup91TriggerResearch.Mode mode,
                      List<Setup91TriggerResearch.Result> results) { this(symbol, start, end, mode, results, -1); }
    }
    public record Session(LocalDate date, long lasts, int entries) {}
    private final String template;
    public Setup91TriggerResearchRunner(String template) { this.template = template; }

    public Report run(String symbol, LocalDate start, LocalDate end, Setup91TriggerResearch.Mode mode) throws Exception {
        return run(symbol, start, end, mode, session -> {});
    }

    public Report run(String symbol, LocalDate start, LocalDate end, Setup91TriggerResearch.Mode mode,
                      java.util.function.Consumer<Session> progress) throws Exception {
        Setup91BatchRunner.validateSymbol(symbol);
        if (start == null || end == null || start.isAfter(end)) throw new IllegalArgumentException("Invalid dates");
        var all = new ArrayList<Setup91TriggerResearch.Result>();
        int tradingDays = 0;
        for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Research interrupted");
            var tracker = new Setup91TriggerResearch(mode);
            var day = new Setup91BatchRunner(template).run(symbol, date, tracker);
            var entries = tracker.finishDay();
            all.addAll(entries);
            if (day.lastsProcessed() > 0) tradingDays++;
            progress.accept(new Session(date, day.lastsProcessed(), entries.size()));
        }
        return new Report(symbol, start, end, mode, all, tradingDays);
    }
}
