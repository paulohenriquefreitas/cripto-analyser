package br.com.bauzin.market.panic.panicscanner.application.experimental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExperimentalComparisonCommandTest {
    @TempDir Path directory;
    @Test void purgesDevelopmentLabelsCrossingSplitAndKeepsValidationLabels() {
        var split=LocalDate.of(2026,9,1);
        var c=new ExperimentalComparisonCommand.Case("TEST3",split.minusDays(1),"DEVELOPMENT","RAW_D1","WATCH","NONE",false,null,null,null,
                null,null,null,split,split.plusDays(2),null);
        assertThat(ExperimentalComparisonCommand.evaluable(c,3,split)).isFalse();
        assertThat(ExperimentalComparisonCommand.evaluable(c,10,split)).isFalse();
    }
    @Test void refusesCorruptAndDuplicateRawHistory() throws Exception {
        Path p=directory.resolve("TEST3.mt5.json");
        Files.writeString(p,"[]");
        assertThatThrownBy(()->ExperimentalComparisonCommand.readHistory(p)).hasMessageContaining("Empty");
        String row="{\"time\":1788220800,\"open\":10,\"high\":11,\"low\":9,\"close\":10,\"realVolume\":100}";
        Files.writeString(p,"["+row+","+row+"]");
        assertThatThrownBy(()->ExperimentalComparisonCommand.readHistory(p)).hasMessageContaining("Duplicate");
    }
    @Test void missingOhlcvIsInconclusiveAndDoesNotFabricateZeroComparison() throws Exception {
        Path study=directory.resolve("study");Files.createDirectories(study.resolve("checkpoints"));
        Files.writeString(study.resolve("settings.json"),"""
                {"from":"2026-08-01","to":"2026-09-30","tickers":["TEST3"],"momentum":{},"entry":{}}
                """);
        Files.writeString(study.resolve("checkpoints/TEST3.json"),"""
                {"ticker":"TEST3","observations":[{"signal":{"simulatedDate":"2026-09-01","result":{"status":"WATCH","score":50}},"return3Percent":8,"date3":"2026-09-04"}]}
                """);
        Path out=directory.resolve("out");
        ExperimentalComparisonCommand.main(new String[]{"--study",study.toString(),"--cache",directory.resolve("missing").toString(),
                "--output",out.toString(),"--validation-from","2026-09-01"});
        assertThat(Files.readString(out.resolve("comparison.csv"))).contains("INCONCLUSIVE","LEGACY_SNAPSHOT");
        assertThat(Files.readString(out.resolve("summary.md"))).contains("raw D1 datasets: 0");
    }
    @Test void negativeReturnsAreFalseAlertsAndMissingFutureIsExcluded() {
        LocalDate d=LocalDate.of(2026,9,1);
        var c=new ExperimentalComparisonCommand.Case("TEST3",d,"VALIDATION","RAW_D1","QUALIFIED","NONE",false,null,null,null,
                new java.math.BigDecimal("-1"),new java.math.BigDecimal("10"),null,d.plusDays(3),d.plusDays(7),null);
        assertThat(ExperimentalComparisonCommand.positive(c,3)).isFalse();
        assertThat(ExperimentalComparisonCommand.positive(c,5)).isTrue();
        assertThat(ExperimentalComparisonCommand.evaluable(c,10,d)).isFalse();
        assertThat(ExperimentalComparisonCommand.alert(c,"COMBINED")).isTrue();
    }
    @Test void leadIsMeasuredInObservedSessionsAndUnmatchedIsCensored() {
        LocalDate d=LocalDate.of(2026,9,4);
        var first=new ExperimentalComparisonCommand.Case("TEST3",d,"VALIDATION","RAW_D1","WATCH","ANALYZE_ENTRY",true,null,null,null,null,null,null,null,null,null);
        var next=new ExperimentalComparisonCommand.Case("TEST3",d.plusDays(4),"VALIDATION","RAW_D1","QUALIFIED","NONE",false,null,null,null,null,null,null,null,null,null);
        assertThat(ExperimentalComparisonCommand.withLeads(List.of(first,next)).getFirst().leadSessions()).isEqualTo(1);
        assertThat(ExperimentalComparisonCommand.withLeads(List.of(first)).getFirst().leadSessions()).isNull();
    }
    @Test void overlappingStrongStartsAreCountedAsOneMovementWindow() {
        LocalDate d=LocalDate.of(2026,9,1);List<ExperimentalComparisonCommand.Case> cases=new ArrayList<>();
        for(int i=0;i<3;i++) cases.add(new ExperimentalComparisonCommand.Case("TEST3",d.plusDays(i),"VALIDATION","RAW_D1",i==1?"QUALIFIED":"WATCH",
                "NONE",false,null,null,null,new java.math.BigDecimal("8"),null,null,d.plusDays(i+3),null,null));
        assertThat(ExperimentalComparisonCommand.movementWindows(cases,3,d)).containsExactly(1,1,1);
    }
}
