package br.com.bauzin.market.panic.panicscanner.application.experimental;

import java.io.IOException;
import java.math.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

import static br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalComparisonCommand.*;

/** Report schema 2: explicit denominators, censoring and partition-bounded anticipation. */
final class ExperimentalComparisonReport {
    static final int SCHEMA = 2;
    static final List<String> PARTITIONS = List.of("DEVELOPMENT", "VALIDATION");
    static final List<Integer> HORIZONS = List.of(3, 5, 10);
    enum LabelStatus { AVAILABLE, PURGED_AT_SPLIT, INSUFFICIENT_FUTURE_DATA }

    record Metrics(String partition, String sample, String variant, int horizon,
                   int totalCases, long evaluatedStarts, long missingFutureCases, long purgedCases,
                   long strongStarts, long alerts, long missingFutureAlerts, long purgedAlerts,
                   long coveredStarts, long belowThresholdAlerts, long negativeReturnAlerts,
                   long flatReturnAlerts, long positiveBelowThresholdAlerts, long positiveReturnAlerts,
                   Double coverage, Double belowThresholdRate, Double positiveReturnRate,
                   BigDecimal meanReturn, BigDecimal medianReturn) {}

    record AnticipationMetrics(String partition, int additionalAlertDays, long tickers,
                              long additionalEpisodes, long matched, long boundaryCensored,
                              long endCensored, long noMatchWithin10, Double meanLeadSessions,
                              Double medianLeadSessions, long previouslyQualifiedWithin10,
                              long previousWindowIncomplete) {}

    record Concentration(String partition, LocalDate date, int horizon, long additionalAlerts,
                         long evaluatedAlerts, long missingFutureAlerts, long purgedAlerts,
                         long strongAlerts, long negativeAlerts, BigDecimal meanReturn) {}

    static BigDecimal value(Case c, int horizon) {
        return switch (horizon) { case 3 -> c.return3(); case 5 -> c.return5(); case 10 -> c.return10();
            default -> throw new IllegalArgumentException("Unsupported horizon"); };
    }

    static LocalDate labelDate(Case c, int horizon) {
        return switch (horizon) { case 3 -> c.date3(); case 5 -> c.date5(); case 10 -> c.date10();
            default -> throw new IllegalArgumentException("Unsupported horizon"); };
    }

    static LabelStatus labelStatus(Case c, int horizon, LocalDate split) {
        LocalDate end = labelDate(c, horizon);
        if (end == null || value(c, horizon) == null) return LabelStatus.INSUFFICIENT_FUTURE_DATA;
        if (c.partition().equals("DEVELOPMENT") && !end.isBefore(split)) return LabelStatus.PURGED_AT_SPLIT;
        return LabelStatus.AVAILABLE;
    }

    static boolean included(Case c, String partition) {
        return c.partition().equals(partition) && (!partition.equals("DEVELOPMENT") || !c.ticker().equals("MOVI3"));
    }

    static boolean conclusive(Case c) { return !c.experimentalSignal().equals("INCONCLUSIVE"); }
    static boolean liquid(Case c) { return c.currentResult() != null && c.currentResult().path("checks").path("liquidityAccepted").asBoolean(false); }

    static Metrics metrics(List<Case> rows, String partition, String sample, String variant,
                           int horizon, LocalDate split) {
        List<Case> valid = rows.stream().filter(c -> labelStatus(c, horizon, split) == LabelStatus.AVAILABLE).toList();
        Predicate<Case> selected = c -> variant.equals("ADDITIONAL") ? ExperimentalAnticipation.additional(c) : alert(c, variant);
        List<Case> alerts = valid.stream().filter(selected).toList();
        long strong = valid.stream().filter(c -> positive(c, horizon)).count();
        long covered = alerts.stream().filter(c -> positive(c, horizon)).count();
        long negative = alerts.stream().filter(c -> value(c, horizon).signum() < 0).count();
        long flat = alerts.stream().filter(c -> value(c, horizon).signum() == 0).count();
        long positiveBelow = alerts.stream().filter(c -> value(c, horizon).signum() > 0 && !positive(c, horizon)).count();
        List<BigDecimal> returns = alerts.stream().map(c -> value(c, horizon)).sorted().toList();
        return new Metrics(partition, sample, variant, horizon, rows.size(), valid.size(),
                countLabels(rows, horizon, split, LabelStatus.INSUFFICIENT_FUTURE_DATA),
                countLabels(rows, horizon, split, LabelStatus.PURGED_AT_SPLIT), strong, alerts.size(),
                countLabels(rows.stream().filter(selected).toList(), horizon, split, LabelStatus.INSUFFICIENT_FUTURE_DATA),
                countLabels(rows.stream().filter(selected).toList(), horizon, split, LabelStatus.PURGED_AT_SPLIT),
                covered, alerts.size() - covered, negative, flat, positiveBelow, alerts.size() - negative - flat,
                ratio(covered, strong), ratio(alerts.size() - covered, alerts.size()), ratio(alerts.size() - negative - flat, alerts.size()),
                average(returns), median(returns));
    }

    private static long countLabels(List<Case> rows, int h, LocalDate split, LabelStatus status) {
        return rows.stream().filter(c -> labelStatus(c, h, split) == status).count();
    }
    private static Double ratio(long n, long d) { return d == 0 ? null : (double) n / d; }
    private static BigDecimal average(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }
    private static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) return null;
        int i = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(i) : sorted.get(i - 1).add(sorted.get(i)).divide(BigDecimal.valueOf(2));
    }

    static AnticipationMetrics anticipation(List<Case> rows, String partition,
                                            Map<String, ExperimentalAnticipation.Audit> audits) {
        List<Case> extra = rows.stream().filter(c -> included(c, partition) && conclusive(c))
                .filter(ExperimentalAnticipation::additional).toList();
        List<ExperimentalAnticipation.Audit> selected = extra.stream()
                .map(c -> audits.get(ExperimentalAnticipation.key(c.ticker(), c.date()))).toList();
        List<BigDecimal> matched = selected.stream().filter(a -> a.status() == ExperimentalAnticipation.Status.MATCHED)
                .map(a -> BigDecimal.valueOf(a.leadSessions())).sorted().toList();
        BigDecimal mean = average(matched), middle = median(matched);
        return new AnticipationMetrics(partition, extra.size(), extra.stream().map(Case::ticker).distinct().count(),
                selected.stream().filter(ExperimentalAnticipation.Audit::additionalEpisodeStart).count(), matched.size(),
                countLeadStatus(selected, ExperimentalAnticipation.Status.PARTITION_BOUNDARY),
                countLeadStatus(selected, ExperimentalAnticipation.Status.INSUFFICIENT_SESSIONS),
                countLeadStatus(selected, ExperimentalAnticipation.Status.NO_MATCH_WITHIN_10),
                mean == null ? null : mean.doubleValue(), middle == null ? null : middle.doubleValue(),
                selected.stream().filter(ExperimentalAnticipation.Audit::previousQualifiedWithin10Sessions).count(),
                selected.stream().filter(a -> !a.previousWindowComplete()).count());
    }
    private static long countLeadStatus(List<ExperimentalAnticipation.Audit> rows, ExperimentalAnticipation.Status status) {
        return rows.stream().filter(a -> a.status() == status).count();
    }

    static List<Concentration> concentration(List<Case> cases, LocalDate split) {
        List<Concentration> results = new ArrayList<>();
        for (String part : PARTITIONS) {
            List<Case> extra = cases.stream().filter(c -> included(c, part) && conclusive(c))
                    .filter(ExperimentalAnticipation::additional).toList();
            for (LocalDate d : extra.stream().map(Case::date).distinct().sorted().toList()) {
                List<Case> day = extra.stream().filter(c -> c.date().equals(d)).toList();
                for (int h : HORIZONS) {
                    Metrics m = metrics(day, part, "PAIRED", "ADDITIONAL", h, split);
                    results.add(new Concentration(part, d, h, day.size(), m.alerts(), m.missingFutureAlerts(),
                            m.purgedAlerts(), m.coveredStarts(), m.negativeReturnAlerts(), m.meanReturn()));
                }
            }
        }
        return results;
    }

    static void write(Path output, List<Case> cases, LocalDate split, int tickers, int complete, int drift) throws IOException {
        Map<String, ExperimentalAnticipation.Audit> audits = ExperimentalAnticipation.audit(cases);
        List<Metrics> metrics = new ArrayList<>(), additional = new ArrayList<>();
        List<AnticipationMetrics> anticipation = new ArrayList<>();
        for (String part : PARTITIONS) {
            for (String sample : List.of("ALL_BASELINE", "PAIRED", "PAIRED_LIQUID")) {
                List<Case> rows = cases.stream().filter(c -> included(c, part))
                        .filter(c -> sample.equals("ALL_BASELINE") || conclusive(c))
                        .filter(c -> !sample.equals("PAIRED_LIQUID") || liquid(c)).toList();
                for (String variant : sample.equals("ALL_BASELINE") ? List.of("CURRENT") : List.of("CURRENT", "COMBINED")) {
                    for (int h : HORIZONS) metrics.add(metrics(rows, part, sample, variant, h, split));
                }
            }
            List<Case> paired = cases.stream().filter(c -> included(c, part) && conclusive(c)).toList();
            for (int h : HORIZONS) additional.add(metrics(paired, part, "PAIRED", "ADDITIONAL", h, split));
            anticipation.add(anticipation(cases, part, audits));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("metrics.json").toFile(), metrics);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("additional-metrics.json").toFile(), additional);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("anticipation-metrics.json").toFile(), anticipation);
        List<Concentration> concentration = concentration(cases, split);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("concentration.json").toFile(), concentration);
        writeAudits(output, cases, audits, split);
        writeDaily(output, cases);
        writePatterns(output, cases, split);
        StringBuilder summary = new StringBuilder("# Experimental Panic comparison — report schema 2\n\n")
                .append("Strategy version: ").append(ExperimentalEntryAnalyzer.VERSION).append(". Production flag is not changed by this command.\n\n")
                .append("Universe from saved study: ").append(tickers).append(" tickers; raw D1 datasets: ").append(complete)
                .append("; unavailable raw: ").append(tickers - complete).append(". Tickers with exported sessions: ")
                .append(cases.stream().map(Case::ticker).distinct().count()).append(". Exported cases: ").append(cases.size()).append(".\n\n")
                .append("Experimental INCONCLUSIVE cases: ").append(cases.stream().filter(c -> !conclusive(c)).count())
                .append(". Legacy snapshots support baseline evaluation only.\n\n")
                .append("Current status differences versus saved checkpoints: ").append(drift).append(".\n\n")
                .append("Development before ").append(split).append("; validation from ").append(split)
                .append(". MOVI3 excluded from development metrics. Return labels reaching validation are purged per horizon. Anticipation pairs cannot cross partitions.\n\n")
                .append("D+3/5/10 are trading sessions; strong-return thresholds are 5/10/15%. Returns are close-to-close and overlap; they are not executed trades or net profit.\n\n")
                .append("ALL_BASELINE includes legacy and inconclusive cases. PAIRED includes conclusive experimental evaluations. PAIRED_LIQUID further requires the existing liquidity check. Compare variants within the same sample.\n\n")
                .append("| Partition | Sample | Variant | Horizon | Evaluated starts | Strong starts | Alerts | Covered | Coverage | Below target | Negative return | Missing future alerts | Purged alerts |\n")
                .append("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Metrics m : metrics) summary.append("| ").append(m.partition()).append(" | ").append(m.sample()).append(" | ").append(m.variant())
                .append(" | D+").append(m.horizon()).append(" | ").append(m.evaluatedStarts()).append(" | ").append(m.strongStarts())
                .append(" | ").append(m.alerts()).append(" | ").append(m.coveredStarts()).append(" | ").append(percent(m.coverage()))
                .append(" | ").append(m.belowThresholdAlerts()).append(" | ").append(m.negativeReturnAlerts())
                .append(" | ").append(m.missingFutureAlerts()).append(" | ").append(m.purgedAlerts()).append(" |\n");
        summary.append("\nBelow target includes negative, flat and positive returns smaller than the threshold. It is not a count of losing trades. Missing future and purged labels never count as failures. Undefined rates are null in JSON.\n\n")
                .append("## Additional alerts\n\n| Partition | Horizon | Evaluated alerts | Strong returns | Positive below target | Negative | Flat | Missing future | Purged | Mean return % | Median return % |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Metrics m : additional) summary.append("| ").append(m.partition()).append(" | D+").append(m.horizon()).append(" | ")
                .append(m.alerts()).append(" | ").append(m.coveredStarts()).append(" | ").append(m.positiveBelowThresholdAlerts())
                .append(" | ").append(m.negativeReturnAlerts()).append(" | ").append(m.flatReturnAlerts())
                .append(" | ").append(m.missingFutureAlerts()).append(" | ").append(m.purgedAlerts())
                .append(" | ").append(number(m.meanReturn())).append(" | ").append(number(m.medianReturn())).append(" |\n");
        summary.append("\n## Anticipation by partition\n\nOnly additional alerts are included. Matched means next QUALIFIED observed within ten ticker sessions in the same partition. Prior qualification uses past context, including earlier partitions.\n\n")
                .append("| Partition | Additional alert-days | Tickers | Episode starts | Matched | Boundary censored | End censored | Complete window without match | Mean lead | Median lead | Prior QUALIFIED within 10 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (AnticipationMetrics m : anticipation) summary.append("| ").append(m.partition()).append(" | ").append(m.additionalAlertDays())
                .append(" | ").append(m.tickers()).append(" | ").append(m.additionalEpisodes()).append(" | ").append(m.matched())
                .append(" | ").append(m.boundaryCensored()).append(" | ").append(m.endCensored()).append(" | ").append(m.noMatchWithin10())
                .append(" | ").append(number(m.meanLeadSessions())).append(" | ").append(number(m.medianLeadSessions()))
                .append(" | ").append(m.previouslyQualifiedWithin10()).append(" |\n");
        summary.append("\nUnmatched alerts have no imputed lead. Means use matched pairs only. Prior qualification is not evidence of first discovery; incomplete past windows are identified in anticipation.csv. Consecutive additional alert-days count as one episode start, even when crossing the partition boundary.\n\n")
                .append("## Concentration of additional strong returns\n\n| Partition | Horizon | Evaluated alerts | Strong returns | Strong-return tickers | Most concentrated date | Hits on that date | Share of hits |\n")
                .append("|---|---|---:|---:|---:|---|---:|---:|\n");
        for (String part : PARTITIONS) for (int h : HORIZONS) {
            List<Case> extra = cases.stream().filter(c -> included(c, part) && conclusive(c) && ExperimentalAnticipation.additional(c))
                    .filter(c -> labelStatus(c, h, split) == LabelStatus.AVAILABLE).toList();
            List<Case> hits = extra.stream().filter(c -> positive(c, h)).toList();
            Map<LocalDate, Long> byDay = new TreeMap<>();
            hits.forEach(c -> byDay.merge(c.date(), 1L, Long::sum));
            var most = byDay.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            long n = most == null ? 0 : most.getValue();
            summary.append("| ").append(part).append(" | D+").append(h).append(" | ").append(extra.size()).append(" | ").append(hits.size())
                    .append(" | ").append(hits.stream().map(Case::ticker).distinct().count()).append(" | ").append(most == null ? "undefined" : most.getKey())
                    .append(" | ").append(n).append(" | ").append(percent(ratio(n, hits.size()))).append(" |\n");
        }
        summary.append("\nConcentration is descriptive; the command does not exclude the best date or tune criteria. concentration.json reports every date, including missing labels.\n\n")
                .append("## Non-overlapping movement windows\n\nGreedy windows per ticker/horizon. Coverage requires an alert after window start and strictly before its label session. It does not imply capturing the full window return.\n\n")
                .append("| Partition | Horizon | Windows | Current covered | Combined covered |\n|---|---|---:|---:|---:|\n");
        for (String part : PARTITIONS) for (int h : HORIZONS) {
            List<Case> selected = cases.stream().filter(c -> included(c, part) && conclusive(c)).toList();
            long[] windows = movementWindows(selected, h, split);
            summary.append("| ").append(part).append(" | D+").append(h).append(" | ").append(windows[0]).append(" | ")
                    .append(windows[1]).append(" | ").append(windows[2]).append(" |\n");
        }
        appendDailySummary(summary, cases);
        appendMoviAudit(summary, cases);
        Files.writeString(output.resolve("summary.md"), summary);
    }

    private static String number(Number value) {
        return value == null ? "undefined" : String.format(Locale.ROOT, "%.2f", value.doubleValue());
    }
    private static String percent(Double value) { return value == null ? "undefined" : String.format(Locale.ROOT, "%.2f%%", 100 * value); }

    private static void writeAudits(Path output, List<Case> cases, Map<String, ExperimentalAnticipation.Audit> audits,
                                    LocalDate split) throws IOException {
        try (var lead = Files.newBufferedWriter(output.resolve("anticipation.csv"));
             var labels = Files.newBufferedWriter(output.resolve("evaluation-labels.csv"))) {
            lead.write("ticker,date,partition,currentSignal,experimentalSignal,status,leadSessions,nextQualifiedDate,previousQualifiedWithin10Sessions,previousWindowComplete,additionalEpisodeStart,excludedFromDevelopmentMetrics\n");
            labels.write("ticker,date,partition,horizon,labelStatus,labelDate,returnPercent,excludedFromDevelopmentMetrics\n");
            for (Case c : cases) {
                var a = audits.get(ExperimentalAnticipation.key(c.ticker(), c.date()));
                boolean excluded = c.partition().equals("DEVELOPMENT") && c.ticker().equals("MOVI3");
                if (c.experimentalAlert()) lead.write(String.join(",", csv(c.ticker()), csv(c.date()), csv(c.partition()), csv(c.currentSignal()),
                        csv(c.experimentalSignal()), csv(a.status()), csv(a.leadSessions()), csv(a.nextQualifiedDate()),
                        csv(a.previousQualifiedWithin10Sessions()), csv(a.previousWindowComplete()), csv(a.additionalEpisodeStart()), csv(excluded)) + "\n");
                for (int h : HORIZONS) labels.write(String.join(",", csv(c.ticker()), csv(c.date()), csv(c.partition()), csv(h),
                        csv(labelStatus(c, h, split)), csv(labelDate(c, h)), csv(value(c, h)), csv(excluded)) + "\n");
            }
        }
    }

    private static void writePatterns(Path output, List<Case> cases, LocalDate split) throws IOException {
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (String part : PARTITIONS) for (String pattern : List.of("EARLY_REVERSAL", "ADX_RECOVERY", "PULLBACK_REENTRY")) {
            List<Case> rows = cases.stream().filter(c -> included(c, part) && conclusive(c))
                    .filter(c -> c.experimental() != null && c.experimental().patterns().contains(pattern)).toList();
            patterns.add(Map.of("partition", part, "pattern", pattern, "alertDays", rows.size(),
                    "additionalAlertDays", rows.stream().filter(ExperimentalAnticipation::additional).count(),
                    "additionalHorizons", HORIZONS.stream().map(h -> metrics(rows, part, "PATTERN", "ADDITIONAL", h, split)).toList()));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("pattern-metrics.json").toFile(), patterns);
    }

    private static void writeDaily(Path output, List<Case> cases) throws IOException {
        try (var w = Files.newBufferedWriter(output.resolve("alerts-per-day.csv"))) {
            w.write("date,partition,baselineAll,pairedTickerSessions,baselinePaired,analyzeEntry,combinedPaired,additional,liquidPairedTickerSessions,excludedDevelopmentCases\n");
            for (LocalDate date : cases.stream().map(Case::date).distinct().sorted().toList()) {
                List<Case> all = cases.stream().filter(c -> c.date().equals(date)).toList();
                String part = all.getFirst().partition();
                List<Case> selected = all.stream().filter(c -> included(c, part)).toList();
                List<Case> paired = selected.stream().filter(ExperimentalComparisonReport::conclusive).toList();
                long current = paired.stream().filter(c -> alert(c, "CURRENT")).count();
                long combined = paired.stream().filter(c -> alert(c, "COMBINED")).count();
                w.write(date + "," + part + "," + selected.stream().filter(c -> alert(c, "CURRENT")).count() + "," + paired.size()
                        + "," + current + "," + paired.stream().filter(Case::experimentalAlert).count() + "," + combined
                        + "," + (combined - current) + "," + paired.stream().filter(ExperimentalComparisonReport::liquid).count()
                        + "," + (all.size() - selected.size()) + "\n");
            }
        }
    }

    private static void appendDailySummary(StringBuilder summary, List<Case> cases) {
        summary.append("\n## Daily alert counts before display ranking/limit\n\n| Partition | Sessions | Mean current/day | Mean combined/day | Max combined/day | P95 combined/day |\n|---|---:|---:|---:|---:|---:|\n");
        for (String part : PARTITIONS) {
            List<Case> rows = cases.stream().filter(c -> included(c, part) && conclusive(c)).toList();
            List<LocalDate> days = rows.stream().map(Case::date).distinct().sorted().toList();
            long[] counts = days.stream().mapToLong(d -> rows.stream().filter(c -> c.date().equals(d) && alert(c, "COMBINED")).count()).sorted().toArray();
            Double base = ratio(rows.stream().filter(c -> alert(c, "CURRENT")).count(), days.size());
            Double combined = ratio(rows.stream().filter(c -> alert(c, "COMBINED")).count(), days.size());
            summary.append("| ").append(part).append(" | ").append(days.size()).append(" | ").append(number(base)).append(" | ").append(number(combined))
                    .append(" | ").append(counts.length == 0 ? "undefined" : counts[counts.length - 1])
                    .append(" | ").append(counts.length == 0 ? "undefined" : counts[(int) Math.ceil(.95 * counts.length) - 1]).append(" |\n");
        }
    }

    private static void appendMoviAudit(StringBuilder summary, List<Case> cases) {
        summary.append("\n## MOVI3 requested dates\n\n| Date | Current | Proposed | Patterns / failed checks |\n|---|---|---|---|\n");
        for (String d : List.of("2026-08-25", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-18", "2026-09-19", "2026-09-20", "2026-09-21", "2026-09-22")) {
            Optional<Case> c = cases.stream().filter(r -> r.ticker().equals("MOVI3") && r.date().toString().equals(d)).findFirst();
            summary.append("| ").append(d).append(" | ");
            if (c.isEmpty()) summary.append("NO_SESSION | NO_SESSION | No closed D1 session in available data |\n");
            else summary.append(c.get().currentSignal()).append(" | ").append(c.get().experimentalSignal()).append(" | ")
                    .append(String.join("; ", c.get().experimental().reasons())).append(" |\n");
        }
    }
}
