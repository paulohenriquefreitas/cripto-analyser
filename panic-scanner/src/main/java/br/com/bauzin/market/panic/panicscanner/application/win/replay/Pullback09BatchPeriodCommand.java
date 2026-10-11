package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.PrintStream;
import java.time.LocalDate;
import java.util.Locale;

/** Standalone period command; no Spring context or historical acquisition. */
public final class Pullback09BatchPeriodCommand {
    private Pullback09BatchPeriodCommand() {}
    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3 || args.length > 4) {
            err.println("Usage: Pullback09BatchPeriodCommand SYMBOL START_DATE END_DATE [HISTORY_TEMPLATE]");
            return 2;
        }
        try {
            var runner = new Pullback09BatchPeriodRunner(args.length == 4 ? args[3] : Pullback09BatchRunner.DEFAULT_TEMPLATE);
            print(runner.run(args[0], LocalDate.parse(args[1]), LocalDate.parse(args[2])), out);
            return 0;
        } catch (Exception e) {
            err.println("BATCH PERIOD ERROR: " + e.getMessage());
            return 1;
        }
    }
    public static void print(Pullback09BatchPeriodRunner.Result result, PrintStream out) {
        out.printf("BATCH PERIOD RESEARCH - COMPLETE%nSymbol: %s%nPeriod: %s -> %s (UTC)%nSessions processed: %d%n",
                result.symbol(), result.start(), result.end(), result.sessions().size());
        out.printf(Locale.ROOT, "LASTs: %,d%nElapsed: %.3f s%nLASTs/second: %.2f%n",
                result.lasts(), result.seconds(), result.lastsPerSecond());
        for (var type : Pullback09Setup.EventType.values()) out.println(type.name() + ": " + result.events().get(type));
        out.printf("Outcomes COMPLETE (all windows): %d%nOutcomes INCOMPLETE: %d%n", result.complete(), result.incomplete());
        out.println("Date        LASTs       UP   DOWN   Complete   Incomplete");
        for (var session : result.sessions()) out.printf(Locale.ROOT, "%s  %10d %4d %6d %10d %12d%n",
                session.date(), session.lasts(), session.up(), session.down(), session.complete(), session.incomplete());
        out.println("Units: points. WIN: 5 points = 1 tick; 150 points = 30 ticks.");
        for (var window : result.windows()) {
            out.printf("%nPULLB09 %s - %d MIN%nComplete outcomes: %d; Incomplete: %d%n",
                    window.direction(), window.minutes(), window.complete(), window.incomplete());
            for (var threshold : window.thresholds()) out.printf(Locale.ROOT, ">= %d pts  %d / %d  %s%n",
                    threshold.points(), threshold.reached(), window.complete(), window.complete() == 0 ? "N/A"
                            : String.format(Locale.ROOT, "%.2f%%", threshold.percentage()));
            out.printf("MFE mean: %s; median: %s pts%nMAE mean: %s; median: %s pts%n",
                    number(window.meanMfe()), number(window.medianMfe()), number(window.meanMae()), number(window.medianMae()));
        }
    }
    private static String number(Double value) { return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f", value); }
}
