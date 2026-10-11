package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Dataset research only. No acquisition, models, setup detectors or trading decisions. */
public final class MlDatasetExportCommand {
    private MlDatasetExportCommand() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) throw new IllegalArgumentException("SYMBOL START END NEW_OUTPUT_DIR [LOCAL_ROOT] [LABEL_ATR_MULTIPLIER]");
        String symbol = args[0];
        if (!(symbol.equals("WIN$N") || symbol.matches("[A-Z0-9_]{1,24}"))) throw new IllegalArgumentException("Invalid symbol");
        LocalDate start = LocalDate.parse(args[1]), end = LocalDate.parse(args[2]);
        if (start.isAfter(end)) throw new IllegalArgumentException("Invalid dates");
        Path output = Path.of(args[3]), root = Path.of(args.length >= 5 ? args[4] : "historical-data");
        double threshold = args.length == 6 ? Double.parseDouble(args[5]) : 1.0;
        if (!Double.isFinite(threshold) || threshold <= 0) throw new IllegalArgumentException("Positive label multiplier required");
        if (Files.exists(output)) throw new IllegalArgumentException("Choose a new output directory");
        Files.createDirectories(output);
        List<String> allColumns = new ArrayList<>(MlCandleDataset.featureColumns()); allColumns.addAll(MlCandleDataset.labelColumns());
        Files.writeString(output.resolve("schema.txt"), "features.csv: " + String.join(",", MlCandleDataset.featureColumns())
                + "\ndataset.csv: " + String.join(",", allColumns) + "\n");
        long candles = 0, examples = 0, warmup = 0, horizon = 0, days = 0;
        var failed = new ArrayList<String>(); var directions = new TreeMap<String, Long>(); var firsts = new TreeMap<String, Long>();
        for (String label : List.of("UP", "DOWN", "FLAT")) directions.put(label, 0L);
        for (String label : List.of("UP_FIRST", "DOWN_FIRST", "NONE")) firsts.put(label, 0L);
        try (var featureCsv = Files.newBufferedWriter(output.resolve("features.csv"));
             var datasetCsv = Files.newBufferedWriter(output.resolve("dataset.csv"));
             var daily = Files.newBufferedWriter(output.resolve("daily.csv"))) {
            featureCsv.write(String.join(",", MlCandleDataset.featureColumns()) + "\n");
            datasetCsv.write(String.join(",", allColumns) + "\n");
            daily.write("date,status,candles,valid_examples,warmup_discarded,insufficient_horizon,error\n");
            for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                var engine = new MlCandleDataset(symbol, date, ZoneId.of("America/Sao_Paulo"), threshold);
                br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider.Metadata metadata;
                try {
                    metadata = new LocalMlHistoryReader().read(root, symbol, date, engine::warmUp, engine::onLast);
                } catch (IOException | IllegalArgumentException e) {
                    String error = String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ');
                    failed.add(date + ": " + error);
                    daily.write(date + ",FAILED,0,0,0,0,\"" + error.replace("\"", "\"\"") + "\"\n"); daily.flush();
                    System.out.println(date + " FAILED " + error); continue;
                }
                var session = engine.finish();
                if (session.candles().size() != session.examples().size() + session.warmupDiscarded() + session.horizonDiscarded())
                    throw new IllegalStateException("Accounting mismatch");
                for (var f : session.candles()) featureCsv.write(String.join(",", f.values()) + "\n");
                for (var x : session.examples()) {
                    datasetCsv.write(String.join(",", x.feature().values()) + "," + String.join(",", x.labels()) + "\n");
                    directions.merge(x.labels().get(7), 1L, Long::sum); firsts.merge(x.labels().get(8), 1L, Long::sum);
                }
                candles += session.candles().size(); examples += session.examples().size(); warmup += session.warmupDiscarded();
                horizon += session.horizonDiscarded(); days++;
                daily.write(date + ",OK," + session.candles().size() + "," + session.examples().size() + ","
                        + session.warmupDiscarded() + "," + session.horizonDiscarded() + ",\n");
                featureCsv.flush(); datasetCsv.flush(); daily.flush();
                System.out.printf("%s OK | LASTs %d | candles %d | exemplos %d%n", date, metadata.lasts(), session.candles().size(), session.examples().size());
            }
        }
        String report = "ML DATASET v1 - local only, no training\n" + symbol + " | " + start + " -> " + end
                + "\nCoverage: " + (failed.isEmpty() ? "COMPLETE" : "PARTIAL") + "\nCandles processed: " + candles
                + "\nValid examples: " + examples + "\nWarmup/context discarded: " + warmup + "\nInsufficient/gapped future horizon: " + horizon
                + "\nDays processed (including valid empty days): " + days + "\nMissing/invalid days: " + failed.size()
                + "\nUP/DOWN/FLAT: " + directions + "\nUP_FIRST/DOWN_FIRST/NONE: " + firsts
                + "\nLabel threshold: " + threshold + " * anchor ATR14 (fixed before export; not fitted on September)."
                + "\nDirection: final 15m close versus anchor close; first-touch: original LAST order, inclusive thresholds."
                + "\nFeatures use only closed LAST-derived M5 bars; header official bars are never used."
                + "\nWarmup: authoritative header closed candles for indicators only; never rows, labels or context."
                + "\nContext: 20 preceding consecutive same-session bars, lag20 oldest -> lag1 newest, excludes anchor."
                + "\nSessions: UTC file dates; local hour/minute America/Sao_Paulo. No exchange calendar is inferred."
                + "\nNo gap filling; empty buckets omitted. COMPLETE validation permits final observed bucket closure without invented LAST."
                + "\nVolume/VWAP omitted: LAST volume sum is not independently verified/deduplicated trade volume."
                + "\nTA4J closed-only EMA9/ATR14; indicator state seeded anew per session from warmup."
                + "\nApril-July DISCOVERY; August VALIDATION; September TEST. No statistics fitted to any split."
                + "\nRaw OHLC retained for audit; prefer ATR-normalized fields for later models."
                + "\nFailures:\n" + String.join("\n", failed) + "\n";
        Files.writeString(output.resolve("report.txt"), report); System.out.print(report);
        if (!failed.isEmpty()) System.exit(2);
    }
}
