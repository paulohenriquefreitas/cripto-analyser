package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Console entry point for one local day. Never starts the application or any market data feed.
 */
public final class Pullback09BatchCommand {
    private Pullback09BatchCommand() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: Pullback09BatchCommand SYMBOL DATE [HISTORY_TEMPLATE]");
            System.exit(2);
            return;
        }
        try {
            var runner = new Pullback09BatchRunner(args.length == 3 ? args[2] : Pullback09BatchRunner.DEFAULT_TEMPLATE);
            print(runner.run(args[0], LocalDate.parse(args[1])), System.out);
        } catch (Exception e) {
            System.err.println("BATCH ERROR: " + args[0] + " " + args[1] + ": " + e.getMessage());
            System.exit(1);
        }
    }

    public static void print(Pullback09BatchRunner.Result result, PrintStream out) {
        out.println("BATCH RESEARCH");
        out.println("Symbol: " + result.symbol());
        out.println("Date: " + result.date() + " (UTC)");
        out.printf(Locale.ROOT, "LASTs processed: %,d%n", result.lastsProcessed());
        for (var type : Pullback09Setup.EventType.values()) {
            out.println(type.name().replace('_', ' ') + ": " + result.count(type));
        }
        out.println("SETUP OUTCOMES: " + result.records().size());
        out.println("COMPLETE (all windows): " + result.completeOutcomes());
        out.println("INCOMPLETE: " + (result.records().size() - result.completeOutcomes()));
        out.printf(Locale.ROOT, "Elapsed: %.3f s%nLASTs/second: %.2f%n", result.elapsedSeconds(), result.lastsPerSecond());
        for (var record : result.records()) {
            var snapshot = record.snapshot();
            var outcome = record.outcome();
            out.println();
            out.println(snapshot.setupType().name().replace('_', ' '));
            out.println("C3 bucket: " + time(snapshot.pullb09CandleTimeMsc()));
            out.println("Available: " + time(snapshot.setupAvailableTimeMsc()));
            out.println("Reference price: " + snapshot.referencePrice());
            window(out, 5, outcome.complete5m(), outcome.mfe5m(), outcome.mae5m());
            window(out, 15, outcome.complete15m(), outcome.mfe15m(), outcome.mae15m());
            window(out, 30, outcome.complete30m(), outcome.mfe30m(), outcome.mae30m());
        }
    }

    private static String time(long value) {
        return value + " (" + Instant.ofEpochMilli(value) + ")";
    }

    private static void window(PrintStream out, int minutes, boolean complete, Double mfe, Double mae) {
        out.println(minutes + "m: " + (complete ? "COMPLETE" : "INCOMPLETE"));
        out.println("MFE " + minutes + "m: " + (mfe == null ? "--" : mfe) + " points");
        out.println("MAE " + minutes + "m: " + (mae == null ? "--" : mae) + " points");
    }
}
