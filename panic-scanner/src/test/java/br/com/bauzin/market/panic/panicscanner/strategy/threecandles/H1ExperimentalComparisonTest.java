package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class H1ExperimentalComparisonTest {
    private static final long HOUR=3600000;
    private CandleUpdate hour(int h,double high,double low) {
        return new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",h*HOUR,(high+low)/2,high,low,(high+low)/2),60,true,(h+1)*HOUR);
    }
    @Test void oneOneRequiresStrictNeighborsAndClosedRightHour() {
        var hours=List.of(hour(9,110,90),hour(10,120,80),hour(11,110,90));
        assertThat(H1ExperimentalComparison.analyze(hours,List.of(),List.of(0L),12*HOUR-1).oneOneCandidates()).isEmpty();
        var ready=H1ExperimentalComparison.analyze(hours,List.of(),List.of(0L),12*HOUR);
        assertThat(ready.oneOneCandidates()).hasSize(2).allMatch(l->l.confirmedAtMsc()==12*HOUR && l.extremeTimeMsc()==10*HOUR);
        var equality=List.of(hour(9,120,80),hour(10,120,80),hour(11,110,90));
        assertThat(H1ExperimentalComparison.analyze(equality,List.of(),List.of(0L),12*HOUR).oneOneCandidates()).isEmpty();
    }
    @Test void futureBarsCannotChangeCandidatesEvaluationsRegionsOrReactions() {
        var hours=List.of(hour(9,110,90),hour(10,120,80),hour(11,110,90),hour(12,130,70),hour(13,115,85),hour(14,1000,1));
        for(int i=1;i<=hours.size();i++) {
            long cutoff=hours.get(i-1).availableAtMsc();
            var all=H1ExperimentalComparison.analyze(hours,List.of(),List.of(0L),cutoff);
            assertThat(all).isEqualTo(H1ExperimentalComparison.analyze(hours.subList(0,i),List.of(),List.of(0L),cutoff));
            assertThat(all.oneOneCandidates()).allMatch(l->l.confirmedAtMsc()<=cutoff);
            assertThat(all.regions().stream().flatMap(r->r.tests().stream())).allMatch(t->t.measuredThroughMsc()<=cutoff);
        }
    }
    @Test void consecutiveTouchesFormOneEpisodeAndNeverCreatePivotsByThemselves() {
        var base=new StructuralLevel("base",StructuralLevel.Type.TOP,100,0,2*HOUR,
                new StructuralLevel.Region(100,90,95,"PIVOT_CANDLE"),"STRICT_H1_PREOPEN",StructuralLevel.State.CONFIRMED,null,60);
        var hours=List.of(hour(9,105,95),hour(10,105,95),hour(11,300,290),hour(12,105,95),hour(13,105,95),hour(14,300,290),hour(15,105,95),hour(16,105,95));
        var comparison=H1ExperimentalComparison.analyze(hours,List.of(base),List.of(0L),17*HOUR);
        var region=comparison.regions().stream().filter(r->r.pivotIds().contains("base")).findFirst().orElseThrow();
        assertThat(region.tests()).hasSize(3);
        assertThat(region.tests().getFirst().hours()).isEqualTo(2);
        assertThat(region.tests().getFirst().reactionUpPoints()).isEqualTo(200);
        assertThat(region.tests()).allMatch(t->t.startMsc()>=base.confirmedAtMsc() && t.measuredThroughMsc()<=17*HOUR);
        assertThat(comparison.oneOneCandidates()).noneMatch(l->l.price()==100);
        assertThat(region.high()-region.low()).isLessThanOrEqualTo(H1ExperimentalComparison.REGION_WIDTH);
    }
    @Test @EnabledIfSystemProperty(named="threecandles.nativeH1",matches=".+")
    void nativeOctober16ComparisonPreservesBaselineAndFindsFourAdditionalPivots() throws Exception {
        var json=new ObjectMapper();
        var nativeData=json.readTree(Path.of(System.getProperty("threecandles.nativeH1")).toFile());
        var bars=new ArrayList<CandleUpdate>();
        for(var c:nativeData.path("series").path("M5")) {
            long t=c.path("time").asLong()*1000;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",t,c.path("open").asDouble(),c.path("high").asDouble(),
                    c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        var date=LocalDate.of(2026,10,16);long opening=date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var baseline=H1SessionPreparation.prepare("WIN$N",date,opening,bars);
        var plan=H1SessionPreparation.prepare("WIN$N",date,opening,bars,H1DetectionMode.EXPERIMENTAL_1_1);
        var loader=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistoryLoader.class);
        org.mockito.Mockito.when(loader.load("WIN$N",date,5)).thenReturn(bars);
        var controller=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController(
                org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistory.class),
                org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleLive.class),
                new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol("WINZ26"),loader);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/three-candles/replays")
                .contentType("application/json").content("{\"symbol\":\"WIN$N\",\"date\":\"2026-10-16\",\"startTime\":\"09:00\",\"timeframeMinutes\":5,\"h1DetectionMode\":\"EXPERIMENTAL_1_1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.structure.preparation.experimental.additionalCandidates.length()").value(4));
        assertThat(baseline.detectionMode()).isEqualTo(H1DetectionMode.STRICT_2_2); assertThat(baseline.experimental()).isNull();
        var comparison=plan.experimental();
        assertThat(comparison.strictCandidates()).hasSize(9).containsExactlyElementsOf(baseline.candidates());
        assertThat(plan.levels()).containsAll(baseline.levels());
        assertThat(comparison.oneOneCandidates()).hasSize(13);
        assertThat(comparison.additionalCandidates()).extracting(StructuralLevel::price).containsExactly(210480.0,211210.0,209215.0,210095.0);
        assertThat(comparison.baselineMatches()).hasSize(9);
        assertThat(plan.candidates()).hasSize(13).allMatch(l->l.confirmedAtMsc()<=opening);
        assertThat(comparison.evaluations()).hasSize(60);
        for(var region:comparison.regions()) {
            long bandKnownAt=plan.candidates().stream().filter(l->region.pivotIds().contains(l.id())).mapToLong(StructuralLevel::confirmedAtMsc).max().orElseThrow();
            assertThat(region.tests()).allMatch(t->t.startMsc()>=bandKnownAt && t.measuredThroughMsc()<=opening);
        }
        var expected=new TreeMap<String,Long>();
        var h=nativeData.path("series").path("H1");
        for(int i=1;i<h.size()-1;i++) {
            var c=h.get(i);long time=c.path("time").asLong()*1000;
            long confirmation=h.get(i+1).path("time").asLong()*1000+HOUR;
            if(!plan.sourceSessions().contains(LocalDate.ofEpochDay(time/86400000L).toString()) || confirmation>opening) continue;
            if(c.path("high").asDouble()>h.get(i-1).path("high").asDouble() && c.path("high").asDouble()>h.get(i+1).path("high").asDouble())
                expected.put("TOP:"+time+":"+c.path("high").asDouble(),confirmation);
            if(c.path("low").asDouble()<h.get(i-1).path("low").asDouble() && c.path("low").asDouble()<h.get(i+1).path("low").asDouble())
                expected.put("BOTTOM:"+time+":"+c.path("low").asDouble(),confirmation);
        }
        var actual=new TreeMap<String,Long>();
        comparison.oneOneCandidates().forEach(l->actual.put(l.type()+":"+l.extremeTimeMsc()+":"+l.price(),l.confirmedAtMsc()));
        assertThat(actual).isEqualTo(expected);
        assertThat(plan).isEqualTo(H1SessionPreparation.prepare("WIN$N",date,opening,bars.stream().filter(b->b.availableAtMsc()<=opening).toList(),H1DetectionMode.EXPERIMENTAL_1_1));
        var replay=new ThreeCandleReplay("WIN$N",5,bars,opening,plan);
        while(replay.status()!=ThreeCandleReplay.Status.COMPLETED) {
            assertThat(replay.pipeline().structure(1).preparation()).isEqualTo(plan);
            assertThat(replay.pipeline().structure(1).levels().stream().filter(l->l.timeframeMinutes()==60)).containsExactlyElementsOf(plan.levels());
            assertThat(replay.candles()).allMatch(c->c.availableAtMsc()<=replay.availableAtMsc());replay.next();
        }
        replay.reset(); assertThat(replay.pipeline().structure(6).preparation()).isEqualTo(plan);
        String preview=System.getProperty("threecandles.experimentalPreview");
        if(preview!=null) json.writerWithDefaultPrettyPrinter().writeValue(Path.of(preview).toFile(),Map.of(
                "id","experimental-oct16","symbol","WIN$N","timeframeMinutes",5,"status","PAUSED","speed",1,
                "availableAtMsc",replay.availableAtMsc(),"candles",replay.candles(),"structure",replay.pipeline().structure(6)));
        System.out.println("EXPERIMENTAL_H1_OCT16 baseline=9 oneOne=13 additional=4 nativeDifferences=0 lookahead=false");
    }

    @Test void descriptiveBandDoesNotChainDistantPricesOrUseMembersBeforeConfirmation() {
        var baseline=new ArrayList<StructuralLevel>();
        for(int price:new int[]{100,140,180}) baseline.add(new StructuralLevel("pivot-"+price,StructuralLevel.Type.TOP,price,
                HOUR,(price==140?12:2)*HOUR,new StructuralLevel.Region(price,price-5,price-2.5,"PIVOT_CANDLE"),
                "STRICT_H1_PREOPEN",StructuralLevel.State.CONFIRMED,null,60));
        var hours=List.of(hour(9,145,95),hour(10,145,95),hour(11,300,290),hour(12,145,95),hour(13,145,95),hour(14,300,290),hour(15,145,95),hour(16,145,95));
        var study=H1ExperimentalComparison.analyze(hours,baseline,List.of(0L),17*HOUR);
        var group=study.regions().stream().filter(r->r.pivotIds().contains("pivot-100")).findFirst().orElseThrow();
        assertThat(group.pivotIds()).contains("pivot-140").doesNotContain("pivot-180");
        assertThat(group.tests()).hasSize(2).allMatch(t->t.startMsc()>=12*HOUR);
        assertThat(study.regions()).allMatch(r->r.high()-r.low()<=50);
    }
}
