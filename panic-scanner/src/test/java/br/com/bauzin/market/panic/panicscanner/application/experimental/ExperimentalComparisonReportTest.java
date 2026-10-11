package br.com.bauzin.market.panic.panicscanner.application.experimental;

import br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalComparisonCommand.*;
import static br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalAnticipation.*;
import static br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalComparisonReport.*;

class ExperimentalComparisonReportTest {
    @TempDir Path directory;
    final LocalDate split = LocalDate.of(2026, 9, 1);

    @Test void anticipationCannotUseQualifiedFromValidationToScoreDevelopment() {
        Case first = row("TEST3", split.minusDays(1), "WATCH", true, true, "2");
        Case next = row("TEST3", split, "QUALIFIED", false, true, "2");
        List<Case> rows = List.of(first, next);
        assertThat(withLeads(rows).getFirst().leadSessions()).isNull();
        Audit audit = audit(rows).get(key(first.ticker(), first.date()));
        assertThat(audit.status()).isEqualTo(Status.PARTITION_BOUNDARY);
        assertThat(audit.nextQualifiedDate()).isNull();
        assertThat(anticipation(rows, "DEVELOPMENT", audit(rows)).boundaryCensored()).isEqualTo(1);
    }

    @Test void anticipationDistinguishesAnIncompleteWindowFromTenSessionsWithoutMatch() {
        List<Case> rows = new ArrayList<>();
        rows.add(row("TEST3", split, "WATCH", true, true, "2"));
        for (int i = 1; i <= 9; i++) rows.add(row("TEST3", split.plusDays(i), "WATCH", false, true, "2"));
        assertThat(audit(rows).get(key("TEST3", split)).status()).isEqualTo(Status.INSUFFICIENT_SESSIONS);
        rows.add(row("TEST3", split.plusDays(10), "WATCH", false, true, "2"));
        assertThat(audit(rows).get(key("TEST3", split)).status()).isEqualTo(Status.NO_MATCH_WITHIN_10);
        rows.add(row("TEST3", split.plusDays(11), "QUALIFIED", false, true, "2"));
        assertThat(withLeads(rows).getFirst().leadSessions()).isNull();
    }

    @Test void anticipationUsesOnlyTheSameTickerAndHandlesInterleavedRows() {
        List<Case> rows = List.of(row("TEST3", split, "WATCH", true, true, "1"),
                row("OTHR3", split.plusDays(1), "QUALIFIED", false, true, "1"),
                row("TEST3", split.plusDays(4), "QUALIFIED", false, true, "1"));
        assertThat(withLeads(rows).getFirst().leadSessions()).isEqualTo(1);
        assertThat(audit(rows).get(key("TEST3", split)).nextQualifiedDate()).isEqualTo(split.plusDays(4));
    }

    @Test void sameDayQualifiedIsNotAnAdditionalAnticipationPair() {
        List<Case> rows = List.of(row("TEST3", split, "QUALIFIED", true, true, "8"));
        assertThat(audit(rows).get(key("TEST3", split)).status()).isEqualTo(Status.ALREADY_QUALIFIED);
        AnticipationMetrics m = anticipation(rows, "VALIDATION", audit(rows));
        assertThat(m.additionalAlertDays()).isZero();
        assertThat(m.meanLeadSessions()).isNull();
    }

    @Test void priorQualificationIsPastContextAndEpisodeCountsDoNotRepeatConsecutiveAlerts() {
        List<Case> rows = List.of(row("TEST3", split.minusDays(1), "QUALIFIED", false, true, "1"),
                row("TEST3", split, "WATCH", true, true, "1"),
                row("TEST3", split.plusDays(1), "WATCH", true, true, "1"),
                row("TEST3", split.plusDays(2), "WATCH", false, true, "1"),
                row("TEST3", split.plusDays(3), "WATCH", true, true, "1"));
        AnticipationMetrics m = anticipation(rows, "VALIDATION", audit(rows));
        assertThat(m.additionalAlertDays()).isEqualTo(3);
        assertThat(m.additionalEpisodes()).isEqualTo(2);
        assertThat(m.previouslyQualifiedWithin10()).isEqualTo(3);
        assertThat(m.previousWindowIncomplete()).isEqualTo(3);
    }

    @Test void duplicateTickerSessionIsRejectedBeforeLeadsAreAssigned() {
        Case c = row("TEST3", split, "WATCH", true, true, "1");
        assertThatThrownBy(() -> withLeads(List.of(c, c))).hasMessageContaining("Duplicate");
    }

    @Test void missingReturnWithExistingDateIsNotEvaluableAndSplitLabelsArePurged() {
        Case missing = row("TEST3", split, "WATCH", true, true, null);
        assertThat(labelStatus(missing, 3, split)).isEqualTo(LabelStatus.INSUFFICIENT_FUTURE_DATA);
        assertThat(evaluable(missing, 3, split)).isFalse();
        Case crossesSplit = row("TEST3", split.minusDays(1), "WATCH", true, true, "8");
        assertThat(labelStatus(crossesSplit, 3, split)).isEqualTo(LabelStatus.PURGED_AT_SPLIT);
    }

    @Test void belowTargetAndNegativeReturnAreDistinctAndMissingFutureNeverCountsAsFailure() {
        List<Case> rows = List.of(row("TEST3", split, "WATCH", true, true, "7"),
                row("TEST3", split.plusDays(1), "WATCH", true, true, "2"),
                row("TEST3", split.plusDays(2), "WATCH", true, true, "-1"),
                row("TEST3", split.plusDays(3), "WATCH", true, true, "0"),
                row("TEST3", split.plusDays(4), "WATCH", true, true, null));
        Metrics m = metrics(rows, "VALIDATION", "PAIRED", "ADDITIONAL", 3, split);
        assertThat(m.alerts()).isEqualTo(4);
        assertThat(m.coveredStarts()).isEqualTo(1);
        assertThat(m.belowThresholdAlerts()).isEqualTo(3);
        assertThat(m.negativeReturnAlerts()).isEqualTo(1);
        assertThat(m.positiveBelowThresholdAlerts()).isEqualTo(1);
        assertThat(m.flatReturnAlerts()).isEqualTo(1);
        assertThat(m.positiveReturnRate()).isEqualTo(.5);
        assertThat(m.missingFutureAlerts()).isEqualTo(1);
        assertThat(m.meanReturn()).isEqualByComparingTo("2");
        assertThat(m.medianReturn()).isEqualByComparingTo("1");
    }

    @Test void purgedReturnsAreNotFailuresOrMeanReturnInputs() {
        Case c = row("TEST3", split.minusDays(1), "WATCH", true, true, "-99");
        Metrics m = metrics(List.of(c), "DEVELOPMENT", "PAIRED", "ADDITIONAL", 3, split);
        assertThat(m.alerts()).isZero();
        assertThat(m.purgedAlerts()).isEqualTo(1);
        assertThat(m.negativeReturnAlerts()).isZero();
        assertThat(m.meanReturn()).isNull();
        assertThat(m.belowThresholdRate()).isNull();
    }

    @Test void reportExposesCoverageForLiquidUniverseWithTheCorrectDenominator() throws Exception {
        List<Case> rows = List.of(row("TEST3", split, "QUALIFIED", false, true, "8"),
                row("OTHR3", split, "WATCH", false, false, "9"));
        write(directory, rows, split, 2, 2, 0);
        JsonNode metrics = JSON.readTree(directory.resolve("metrics.json").toFile());
        JsonNode paired = find(metrics, "PAIRED", "CURRENT", 3);
        JsonNode liquid = find(metrics, "PAIRED_LIQUID", "CURRENT", 3);
        assertThat(paired.path("strongStarts").asInt()).isEqualTo(2);
        assertThat(paired.path("coverage").asDouble()).isEqualTo(.5);
        assertThat(liquid.path("strongStarts").asInt()).isEqualTo(1);
        assertThat(liquid.path("coverage").asDouble()).isEqualTo(1);
    }

    @Test void concentrationReportsAllDatesWithoutDroppingTheWinningSession() {
        List<Case> rows = List.of(row("TEST3", split, "WATCH", true, true, "1"),
                row("TEST3", split.plusDays(10), "WATCH", true, true, "8"),
                row("OTHR3", split.plusDays(10), "WATCH", true, true, "9"));
        List<Concentration> days = concentration(rows, split).stream().filter(c -> c.horizon() == 3).toList();
        assertThat(days).hasSize(2);
        assertThat(days.getFirst().strongAlerts()).isZero();
        assertThat(days.getLast().strongAlerts()).isEqualTo(2);
        assertThat(days.stream().mapToLong(Concentration::additionalAlerts).sum()).isEqualTo(3);
    }

    @Test void dailyCsvAndMetricsBothExcludeMoviFromDevelopment() throws Exception {
        LocalDate d = split.minusDays(10);
        write(directory, List.of(row("MOVI3", d, "QUALIFIED", false, true, "8"),
                row("TEST3", d, "QUALIFIED", false, true, "8")), split, 2, 2, 0);
        List<String> daily = Files.readAllLines(directory.resolve("alerts-per-day.csv"));
        assertThat(daily.get(1)).isEqualTo(d + ",DEVELOPMENT,1,1,1,0,1,0,1,1");
    }

    @Test void reportOnlyPreservesOriginalCasesSignalsLabelsAndMetadataWithoutReadingProviders() throws Exception {
        Path source = directory.resolve("source"), output = directory.resolve("output");
        Files.createDirectories(source);
        Files.writeString(source.resolve("settings.json"), """
                {"version":"early-entry-v1","validationFrom":"2026-09-01","tickerCount":1,"rawDatasetCount":1,
                "legacyStatusDrift":0,"sourceStudy":"MUST_NOT_READ","sourceSettings":{"to":"2026-09-30"}}
                """);
        Case original = row("TEST3", split.minusDays(1), "WATCH", true, true, "2");
        Case staleLead = new Case(original.ticker(), original.date(), original.partition(), original.evidence(), original.currentSignal(),
                original.experimentalSignal(), true, 1, original.currentResult(), original.experimental(),
                original.return3(), original.return5(), original.return10(), original.date3(), original.date5(), original.date10());
        String content = JSON.writeValueAsString(staleLead) + "\n" + JSON.writeValueAsString(row("TEST3", split, "QUALIFIED", false, true, "2")) + "\n";
        Path cases = source.resolve("cases.ndjson");
        Files.writeString(cases, content);
        main(new String[]{"--report-from", source.toString(), "--output", output.toString()});
        assertThat(Files.readString(cases)).isEqualTo(content);
        Case revised = JSON.readValue(Files.readAllLines(output.resolve("cases.ndjson")).getFirst(), Case.class);
        assertThat(revised.leadSessions()).isNull();
        assertThat(revised.currentResult()).isEqualTo(original.currentResult());
        assertThat(revised.experimental()).isEqualTo(original.experimental());
        assertThat(revised.return3()).isEqualByComparingTo(original.return3());
        JsonNode settings = JSON.readTree(output.resolve("settings.json").toFile());
        assertThat(settings.path("version").asText()).isEqualTo("early-entry-v1");
        assertThat(settings.path("reportSchema").asInt()).isEqualTo(2);
        assertThat(settings.path("operation").asText()).isEqualTo("REPORT_ONLY");
        assertThat(settings.path("sourceCasesSha256").asText()).hasSize(64);
        assertThat(Files.readString(output.resolve("anticipation.csv"))).contains("PARTITION_BOUNDARY");
        assertThat(Files.readString(output.resolve("evaluation-labels.csv"))).contains("PURGED_AT_SPLIT");
    }

    @Test void reportOnlyRefusesFetchingAndChangingTheSplit() {
        assertThatThrownBy(() -> main(new String[]{"--report-from", "MUST_NOT_READ", "--output", directory.resolve("out").toString(),
                "--fetch-missing", "true"})).hasMessageContaining("Report-only accepts only");
        assertThatThrownBy(() -> main(new String[]{"--report-from", "MUST_NOT_READ", "--output", directory.resolve("out").toString(),
                "--validation-from", "2026-08-01"})).hasMessageContaining("Report-only accepts only");
    }

    @Test void reportOnlyRefusesToOverwriteSourceResults() {
        assertThatThrownBy(() -> main(new String[]{"--report-from", directory.toString(), "--output", directory.toString()}))
                .hasMessageContaining("Output already exists");
    }

    Case row(String ticker, LocalDate date, String status, boolean experimental, boolean liquid, String r3) {
        JsonNode result = JSON.valueToTree(Map.of("score", 80, "status", status, "checks", Map.of("liquidityAccepted", liquid)));
        return new Case(ticker, date, partition(date, split), "RAW_D1", status, experimental ? "ANALYZE_ENTRY" : "NONE", experimental,
                null, result, new ExperimentalEntryAnalysis(experimental ? "ANALYZE_ENTRY" : "NONE",
                experimental ? List.of("EARLY_REVERSAL") : List.of(), Map.of(), List.of()),
                r3 == null ? null : new BigDecimal(r3), null, null, date.plusDays(3), null, null);
    }

    JsonNode find(JsonNode metrics, String sample, String variant, int horizon) {
        for (JsonNode row : metrics) if (row.path("partition").asText().equals("VALIDATION")
                && row.path("sample").asText().equals(sample) && row.path("variant").asText().equals(variant)
                && row.path("horizon").asInt() == horizon) return row;
        throw new AssertionError("Metric missing");
    }
}
