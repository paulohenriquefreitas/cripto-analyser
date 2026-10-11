package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.time.LocalDate;

/** Standalone entry point: no Spring server, Replay, research or frontend. */
public final class HistoricalDatasetImportCommand {
    private HistoricalDatasetImportCommand() {}

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 6) {
            System.err.println("Usage: HistoricalDatasetImportCommand SYMBOL FROM TO [HISTORY_TEMPLATE] [PYTHON] [SCRIPT]");
            System.exit(2);
            return;
        }
        try {
            var history = new ReplayHistoryProvider(
                    args.length > 3 ? args[3] : "historical-data/{symbol}/{date}.ndjson",
                    args.length > 4 ? args[4] : "python",
                    args.length > 5 ? args[5] : "panic-scanner/scripts/mt5_price_history.py");
            var result = new HistoricalDatasetOrchestrator(history, new HistoricalDatasetImporter(history))
                    .prepare(args[0], LocalDate.parse(args[1]), LocalDate.parse(args[2]));
            System.out.printf("Requested sessions: %d; Cached: %d; Imported: %d; Failed: %d%n",
                    result.requestedSessions(), result.cached(), result.imported(), result.failed());
            result.failures().forEach(failure ->
                    System.err.printf("FAILED %s: %s%n", failure.date(), failure.message()));
            if (result.failed() > 0) System.exit(1);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
