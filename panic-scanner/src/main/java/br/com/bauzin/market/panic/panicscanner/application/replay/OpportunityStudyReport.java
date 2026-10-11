package br.com.bauzin.market.panic.panicscanner.application.replay;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
import static br.com.bauzin.market.panic.panicscanner.application.replay.PanicOpportunityStudy.*;

public final class OpportunityStudyReport {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    private static final List<String> FILES = List.of("opportunities.csv", "missed-opportunities.csv", "detected-early.csv",
            "lost-after-detection.csv", "false-positives.csv");
    private static final String HEADER = "date,ticker,classification,signalStatus,momentumScore,entryStatus,entryScore,rank,rankingFinal,close,sma9,sma21,rsi9,adx14,relativeVolume20,averageFinancialVolume20,distanceFromSma21Percent,return5PastPercent,return20PastPercent,trend,closeAboveSma21,sma9AboveSma21,rsiInsideRange,adxAccepted,liquidityAccepted,historyAccepted,blockingChecks,mainReason,returnD3Percent,returnD5Percent,returnD10Percent,dateD3,dateD5,dateD10,lastQualifiedDate,lastQualifiedScore,sessionsSinceLastSignal,lostExclusivelyByRsiAbove70,entryAnalysisJson,signalJson";
    private OpportunityStudyReport() {}

    public static void initialize(Path output) throws IOException {
        Files.createDirectories(output);
        for (String file : FILES) Files.writeString(output.resolve(file), HEADER + "\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("unavailable-tickers.csv"), "ticker,error\n", StandardCharsets.UTF_8);
    }

    public static void append(Path output, TickerResult ticker, Map<String, Integer> ranks, boolean complete) throws IOException {
        if (ticker.error() != null) {
            Files.writeString(output.resolve("unavailable-tickers.csv"), quote(ticker.ticker()) + "," + quote(ticker.error()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return;
        }
        Map<String, StringBuilder> text = new HashMap<>();
        for (var observation : ticker.observations()) {
            String row = csv(observation, ranks.get(observation.signal().simulatedDate() + "/" + ticker.ticker()), complete);
            text.computeIfAbsent("opportunities.csv", f -> new StringBuilder()).append(row);
            String file = switch (observation.classification()) {
                case DETECTED_EARLY -> "detected-early.csv";
                case LOST_AFTER_DETECTION -> "lost-after-detection.csv";
                case MISSED_OPPORTUNITY -> "missed-opportunities.csv";
                case FALSE_POSITIVE -> "false-positives.csv";
                default -> null;
            };
            if (file != null) text.computeIfAbsent(file, f -> new StringBuilder()).append(row);
        }
        for (var file : text.entrySet()) Files.writeString(output.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String csv(Observation o, Integer rank, boolean complete) throws IOException {
        var r = o.signal().result(); var a = r.technicalAnalysis(); var e = r.entryAnalysis(); var c = r.checks();
        List<Object> values = new ArrayList<>(Arrays.asList(o.signal().simulatedDate(), r.ticker(), o.classification(), r.status(),
                r.score(), e == null ? null : e.status(), e == null ? null : e.score(), rank, complete, r.lastClose()));
        values.addAll(a == null ? Collections.nCopies(10, null) : Arrays.asList(a.sma9(),a.sma21(),a.rsi9(),a.adx14(),
                a.relativeVolume20(),a.averageFinancialVolume20(),a.distanceFromSma21Percent(),a.return5DaysPercent(),a.return20DaysPercent(),a.trend()));
        values.addAll(Arrays.asList(c.closeAboveSma21(),c.sma9AboveSma21(),c.rsiInsideRange(),c.adxAccepted(),c.liquidityAccepted(),c.historyAccepted(),
                String.join(";",o.signal().blockingChecks()),o.signal().mainReason(),o.return3Percent(),o.return5Percent(),o.return10Percent(),
                o.date3(),o.date5(),o.date10(),o.lastQualifiedDate(),o.lastQualifiedScore(),o.sessionsSinceLastSignal(),
                o.lostExclusivelyByRsiAbove70(),JSON.writeValueAsString(e),JSON.writeValueAsString(r)));
        return String.join(",", values.stream().map(OpportunityStudyReport::quote).toList()) + "\n";
    }
    private static String quote(Object value) { return "\"" + (value == null ? "" : value.toString().replace("\"","\"\"")) + "\""; }

    public static void summary(Path output, Settings settings, List<TickerResult> completed, boolean finished) throws IOException {
        List<Observation> all = completed.stream().flatMap(t -> t.observations().stream()).toList();
        Map<Classification, Long> counts = new EnumMap<>(Classification.class);
        all.forEach(o -> counts.merge(o.classification(), 1L, Long::sum));
        long detected = counts.getOrDefault(Classification.DETECTED_EARLY,0L);
        long lost = counts.getOrDefault(Classification.LOST_AFTER_DETECTION,0L), missed = counts.getOrDefault(Classification.MISSED_OPPORTUNITY,0L);
        long fp = counts.getOrDefault(Classification.FALSE_POSITIVE,0L), opportunities = detected + lost + missed;
        var failures = all.stream().filter(o -> o.classification()==Classification.LOST_AFTER_DETECTION || o.classification()==Classification.MISSED_OPPORTUNITY).toList();
        Map<String, Long> causes = new TreeMap<>();
        failures.forEach(o -> o.signal().blockingChecks().forEach(c -> causes.merge(c,1L,Long::sum)));
        failures.stream().filter(o -> o.signal().result().score() < settings.momentum().minimumQualifiedScore())
                .forEach(o -> causes.merge("momentumScoreBelowMinimum",1L,Long::sum));
        long rsiOnly = failures.stream().filter(Observation::lostExclusivelyByRsiAbove70).count();
        StringBuilder s = new StringBuilder("# Panic opportunity study\n\nStatus: ").append(finished ? "COMPLETE" : "IN_PROGRESS - rankings provisional")
                .append("\n\nInterval: ").append(settings.from()).append(" to ").append(settings.to())
                .append("\nTickers processed: ").append(completed.size()).append("/").append(settings.tickers().size())
                .append("\nUnavailable tickers: ").append(completed.stream().filter(t->t.error()!=null).count())
                .append("\nTicker/session observations: ").append(all.size()).append("\n\n");
        for (var kind : Classification.values()) s.append("- ").append(kind).append(": ").append(counts.getOrDefault(kind,0L)).append("\n");
        s.append("\nDetection rate: ").append(rate(detected,opportunities))
                .append("\nFalse negatives (MISSED + LOST): ").append(lost+missed).append(" (").append(rate(lost+missed,opportunities)).append(")")
                .append("\nFalse discovery proportion (FP / evaluated QUALIFIED): ").append(rate(fp,fp+detected))
                .append("\nFalse positive rate (FP / all fully evaluated non-opportunities): ")
                .append(rate(fp,fp+counts.getOrDefault(Classification.NO_OPPORTUNITY,0L)))
                .append("\n\nLost exclusively because RSI > 70: **").append(rsiOnly).append("**\n\n")
                .append("## Causes of missed/lost opportunities\n\n| Check | Count |\n|---|---:|\n");
        causes.forEach((cause,count)->s.append("| ").append(cause).append(" | ").append(count).append(" |\n"));
        s.append("\n## Top 20 missed/lost opportunities\n\n| Date | Ticker | Classification | D+5 % | D+10 % | Blockers | Last QUALIFIED | Sessions since |\n|---|---|---|---:|---:|---|---|---:|\n");
        failures.stream().sorted(Comparator.comparing(OpportunityStudyReport::maximumReturn).reversed()).limit(20).forEach(o ->
                s.append("| ").append(o.signal().simulatedDate()).append(" | ").append(o.signal().result().ticker()).append(" | ")
                        .append(o.classification()).append(" | ").append(o.return5Percent()).append(" | ").append(o.return10Percent())
                        .append(" | ").append(String.join(";",o.signal().blockingChecks())).append(" | ").append(o.lastQualifiedDate())
                        .append(" | ").append(o.sessionsSinceLastSignal()).append(" |\n"));
        s.append("\nDefinitions: each ticker/session is a case (overlapping windows are not independent trades). Strong opportunity = D+5 >= ")
                .append(settings.return5ThresholdPercent()).append("% OR D+10 >= ").append(settings.return10ThresholdPercent())
                .append("%. DETECTED_EARLY means QUALIFIED on D, not a trade entry. LOST uses the latest preceding QUALIFIED within ")
                .append(settings.momentum().lookbackDays()).append(" calendar days before the study start plus study sessions. ")
                .append("Negative classification requires both D+5 and D+10; incomplete labels are excluded from negative-rate denominators. ")
                .append("Ranks use the original selector within available studied tickers; unselected rows have empty rank. ")
                .append("Universe is current BRAPI discovery when tickers are omitted (survivorship bias); prices are current historical feed versions. ")
                .append("No order/stop/profit simulation. Futures are labels only.\n");
        atomicText(output.resolve("summary.md"), s.toString());
    }
    private static BigDecimal maximumReturn(Observation o) {
        return o.return5Percent()==null ? o.return10Percent() : o.return10Percent()==null ? o.return5Percent() : o.return5Percent().max(o.return10Percent());
    }
    private static String rate(long numerator,long denominator) { return denominator==0 ? "N/A" : String.format(Locale.ROOT,"%.2f%%",100.0*numerator/denominator); }
    static void atomicText(Path path, String text) throws IOException {
        Path temp = path.resolveSibling(path.getFileName()+".part");
        Files.writeString(temp,text,StandardCharsets.UTF_8);
        try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
    }
    static void checkpoint(Path path, Object object) throws IOException { atomicText(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object)); }
}
