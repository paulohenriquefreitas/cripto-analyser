package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/** Local-data audit of the SAME confirmed EMA9 reversals displayed by M5ReplaySession. */
public final class Ema9ReversalBatchCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Ema9ReversalBatchCommand() {}

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 5 && args.length != 6) {
            err.println("Usage: Ema9ReversalBatchCommand SYMBOL START_DATE END_DATE HISTORY_TEMPLATE NEW_OUTPUT_DIR");
            return 1;
        }
        try {
            Setup91BatchRunner.validateSymbol(args[0]);
            if (args.length == 6 && !args[5].equals("Off")) throw new IllegalArgumentException("ATR nos fechamentos desativado; somente Off e aceito");
            var start = LocalDate.parse(args[1]);
            var end = LocalDate.parse(args[2]);
            if (start.isAfter(end)) throw new IllegalArgumentException("Start must be <= end");
            var output = Path.of(args[4]).toAbsolutePath();
            if (Files.exists(output)) throw new IOException("Output already exists; choose a new directory: " + output);
            Files.createDirectories(output);
            return run(args[0], start, end, args[3], output, out, false);
        } catch (Exception e) {
            err.println("EMA9 REPORT ERROR: " + e.getMessage());
            return 1;
        }
    }

    private static int run(String symbol, LocalDate start, LocalDate end, String template,
                           Path output, PrintStream console, boolean atrFilter) throws Exception {
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("symbol", symbol);
        configuration.put("start", start.toString());
        configuration.put("end", end.toString());
        configuration.put("startedAt", Instant.now().toString());
        configuration.put("codeSha256", codeHash());
        configuration.put("historyTemplate", template);
        configuration.put("minEmaDisplacementPoints", Ema9ReversalDetector.DEFAULT_MIN_DISPLACEMENT_POINTS);
        configuration.put("minDirectionalCloseDisplacementPoints", Setup91.CLOSE_CONFIRMATION_POINTS);
        configuration.put("signal", "Setup91.Observation.confirmedReversal");
        configuration.put("atrDisplacementFilter", atrFilter);
        configuration.put("atrBounds", "disabled for close displacement; ATR applies only to entry risk");
        configuration.put("clock", "UTC; closed M5 only; independent daily state; warmup excluded; no synthetic EOF closure");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run.json").toFile(), configuration);
        var monthly = new TreeMap<YearMonth, Totals>();
        var total = new Totals();
        var daily = new Setup91BatchRunner(template);
        try (var report = Files.newBufferedWriter(output.resolve("report.txt"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             var days = Files.newBufferedWriter(output.resolve("daily.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             var events = Files.newBufferedWriter(output.resolve("events.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            java.util.function.Consumer<String> print = line -> {
                console.println(line);
                console.flush();
                try { report.write(line); report.newLine(); report.flush(); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            };
            print.accept("MME9 - MUDANCAS DE DIRECAO CONFIRMADAS (UTC)");
            print.accept("Simbolo: " + symbol + " | Periodo: " + start + " a " + end + " (inclusivo)");
            print.accept("Codigo SHA256: " + configuration.get("codeSha256"));
            print.accept("Confirmacao: dois candles; deslocamento da MME9 > " + Ema9ReversalDetector.DEFAULT_MIN_DISPLACEMENT_POINTS + " pontos.");
            print.accept("Fechamento: deslocamento > " + Setup91.CLOSE_CONFIRMATION_POINTS + " pontos no sentido da reversao.");
            print.accept("Mesmo detector do replay, independente de ARMED, TRIGGERED e posicoes.");
            print.accept("Filtro ATR nos fechamentos: OFF; ATR aplicado somente ao risco da entrada.");
            print.accept("DATA        STATUS        M5      ALTA     BAIXA     TOTAL        LAST");
            days.write("date,status,closedCandles,up,down,total,lasts,datasetSha256,error\n");
            events.write("date,candleTimeMsc,availableAtTimeMsc,direction,ema9,previousEma9\n");
            for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted before " + date);
                Path file = Path.of(template.replace("{symbol}", symbol).replace("{date}", date.toString()));
                String status = "OK", error = "", sha = "";
                long lasts = 0;
                var observations = new ArrayList<Setup91.Observation>();
                if (!Files.isRegularFile(file)) status = "MISSING";
                else {
                    try {
                        var result = daily.observe(symbol, date, observations::add, atrFilter);
                        lasts = result.lastsProcessed();
                        var metadata = JSON.readValue(file.resolveSibling(file.getFileName() + ".metadata.json").toFile(), ReplayHistoryProvider.Metadata.class);
                        sha = metadata.sha256();
                    } catch (IOException | RuntimeException e) {
                        status = "FAILED";
                        error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        observations.clear(); // No counts/events from a day that failed integrity checks.
                    }
                }
                long up = 0, down = 0;
                for (var observation : observations) {
                    if (observation.confirmedReversal().isEmpty()) continue;
                    boolean rising = observation.confirmedReversal().orElseThrow() == Setup91.Candidate.BUY;
                    if (rising) up++; else down++;
                    events.write(date + "," + observation.candle().bucketStartTimeMsc() + "," + observation.availableAtTimeMsc()
                            + "," + (rising ? "UP" : "DOWN") + "," + observation.ema9() + "," + observation.previousEma9() + "\n");
                }
                total.add(status, observations.size(), up, down, lasts);
                monthly.computeIfAbsent(YearMonth.from(date), ignored -> new Totals()).add(status, observations.size(), up, down, lasts);
                print.accept(String.format(java.util.Locale.ROOT, "%s  %-8s %8d %9d %9d %9d %11d", date, status, observations.size(), up, down, up + down, lasts));
                if (!error.isEmpty()) print.accept("  Motivo: " + error.replace('\n', ' ').replace('\r', ' '));
                days.write(date + "," + status + "," + observations.size() + "," + up + "," + down + "," + (up + down) + "," + lasts + "," + sha + "," + csv(error) + "\n");
                days.flush(); events.flush();
            }
            print.accept("");
            print.accept("MES       DIAS_OK AUSENTES FALHAS      ALTA     BAIXA     TOTAL");
            for (var entry : monthly.entrySet()) print.accept(entry.getKey() + " " + entry.getValue().summary());
            print.accept("PERIODO " + total.summary());
            print.accept("Candles fechados: " + total.candles + " | LASTs: " + total.lasts);
            print.accept("Situacao: " + (total.missing + total.failed == 0 ? "COMPLETO" : "PARCIAL - dias ausentes ou invalidos NAO foram contados como zero"));
            print.accept("Arquivos: " + output);
            configuration.put("completedAt", Instant.now().toString());
            configuration.put("complete", total.missing + total.failed == 0);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run.json").toFile(), configuration);
        }
        return total.missing + total.failed == 0 ? 0 : 2;
    }

    private static String csv(String text) { return "\"" + text.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""; }

    private static String codeHash() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var classes = new java.util.TreeSet<String>();
        for (var type : List.of(Setup91.class, Ema9ReversalDetector.class)) {
            classes.add(type.getName());
            for (var nested : type.getDeclaredClasses()) classes.add(nested.getName());
        }
        for (var name : classes) {
            try (var stream = Setup91.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("Missing compiled class " + name);
                digest.update(stream.readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class Totals {
        long ok, missing, failed, candles, up, down, lasts;
        void add(String status, long candles, long up, long down, long lasts) {
            switch (status) { case "OK" -> ok++; case "MISSING" -> missing++; default -> failed++; }
            this.candles += candles; this.up += up; this.down += down; this.lasts += lasts;
        }
        String summary() { return String.format(java.util.Locale.ROOT, "%8d %8d %6d %9d %9d %9d", ok, missing, failed, up, down, up + down); }
    }
}
