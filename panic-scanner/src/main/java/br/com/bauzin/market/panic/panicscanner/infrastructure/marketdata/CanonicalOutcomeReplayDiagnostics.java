package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.*;
import br.com.bauzin.market.panic.panicscanner.domain.replay.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class CanonicalOutcomeReplayDiagnostics {
    private CanonicalOutcomeReplayDiagnostics() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: history.ndjson occurrenceTimeMsc entryPrice targetPoints stopPoints horizonSeconds");
        }
        Path history = Path.of(args[0]);
        long occurrenceTime = Long.parseLong(args[1]);
        double entryPrice = Double.parseDouble(args[2]);
        double targetPoints = Double.parseDouble(args[3]);
        double stopPoints = Double.parseDouble(args[4]);
        long horizonSeconds = Long.parseLong(args[5]);
        RuleOccurrence occurrence = new RuleOccurrence(
                "manual-history", "synthetic", "WINV26", occurrenceTime, entryPrice, RuleDirection.LONG);
        OutcomeEvaluator evaluator = new OutcomeEvaluator(new OutcomeSpecification(
                targetPoints, stopPoints, Duration.ofSeconds(horizonSeconds)));
        OutcomeResult result;
        try (var reader = Files.newBufferedReader(history)) {
            var canonical = new Mt5HistoricalPriceSource();
            result = new CanonicalOutcomeReplayRunner().run(
                    consumer -> canonical.stream(reader, header -> {}, consumer),
                    evaluator, occurrence, 0);
        }
        print(result);
    }

    private static void print(OutcomeResult result) {
        var occurrence = result.occurrence();
        System.out.println("Occurrence: " + occurrence.occurrenceId());
        System.out.println("Entry: " + occurrence.entryPrice());
        System.out.println("Direction: " + occurrence.direction());
        System.out.println("Target: " + result.targetPrice());
        System.out.println("Stop: " + result.stopPrice());
        System.out.println("FirstBarrier: " + result.firstBarrier());
        System.out.println("TargetHitTime: " + optional(result.targetHitTime()));
        System.out.println("StopHitTime: " + optional(result.stopHitTime()));
        System.out.println("MFE: " + result.mfe());
        System.out.println("MAE: " + result.mae());
        System.out.println("TimeToMfe: " + optional(result.timeToMfe()));
        System.out.println("TimeToMae: " + optional(result.timeToMae()));
        System.out.println("FinalPrice: " + optional(result.finalPrice()));
        System.out.println("TerminationReason: " + result.terminationReason());
    }

    private static String optional(java.util.OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "N/A";
    }

    private static String optional(java.util.OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "N/A";
    }
}
