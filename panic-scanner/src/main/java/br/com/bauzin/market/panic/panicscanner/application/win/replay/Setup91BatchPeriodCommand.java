package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.PrintStream;
import java.time.LocalDate;
import java.util.Locale;

/** Standalone local dataset research command; no Spring context or historical acquisition. */
public final class Setup91BatchPeriodCommand {
    private Setup91BatchPeriodCommand() {}

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3 || args.length > 4) {
            err.println("Usage: Setup91BatchPeriodCommand SYMBOL START_DATE END_DATE [HISTORY_TEMPLATE]");
            return 2;
        }
        try {
            var runner = new Setup91BatchPeriodRunner(args.length == 4 ? args[3] : Setup91BatchRunner.DEFAULT_TEMPLATE);
            print(runner.run(args[0], LocalDate.parse(args[1]), LocalDate.parse(args[2])), out);
            return 0;
        } catch (Exception e) {
            err.println("SETUP91 BATCH ERROR: " + e.getMessage());
            return 1;
        }
    }

    public static void print(Setup91BatchPeriodRunner.Result result, PrintStream out) {
        out.printf("SETUP91 BATCH RESEARCH - COMPLETE%nSymbol: %s%nPeriod: %s -> %s (UTC, inclusive)%n",
                result.symbol(), result.start(), result.end());
        out.printf("Days processed: %d%nLASTs: %d%nresearchFlatSlopeThresholdPoints: %s%n",
                result.sessions().size(), result.lasts(), Setup91.DEFAULT_RESEARCH_FLAT_SLOPE_THRESHOLD_POINTS);
        out.println("Date/period  Side   ARMED  TRIGGERED  CANCELLED  Trigger rate (TRIGGERED / ARMED)");
        for (var session : result.sessions()) rows(out, session.date().toString(), session.summary());
        rows(out, "PERIOD", result.summary());
        out.println("Daily state resets; pending setups at EOF remain in ARMED counts and are not force-cancelled.");
    }

    private static void rows(PrintStream out, String label, Setup91BatchRunner.Summary summary) {
        row(out, label, "BUY", summary.buy());
        row(out, label, "SELL", summary.sell());
        row(out, label, "TOTAL", summary.total());
    }

    private static void row(PrintStream out, String label, String side, Setup91BatchRunner.Counts counts) {
        String rate = counts.triggerRate() == null ? "N/A" : String.format(Locale.ROOT, "%.2f%%", 100 * counts.triggerRate());
        out.printf(Locale.ROOT, "%s  %s  %d  %d  %d  %s%n", label, side,
                counts.armed(), counts.triggered(), counts.cancelled(), rate);
    }
}
