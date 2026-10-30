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

class M5SessionReplayTest {
    @Test @EnabledIfSystemProperty(named="threecandles.m5Cache",matches=".+")
    void october16EveryClosedPrefixMatchesIndependentPivotsAndLiveIncludingReconnect() throws Exception {
        var json=new ObjectMapper();
        var source=json.readTree(Path.of(System.getProperty("threecandles.m5Cache")).toFile());
        var bars=new ArrayList<CandleUpdate>();
        for(var c:source.path("candles")) {
            long t=c.path("time").asLong()*1000;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",t,c.path("open").asDouble(),
                c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        var date=LocalDate.of(2026,10,16); long day=date.toEpochDay();
        long opening=date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var plan=H1SessionPreparation.prepare("WIN$N",date,opening,bars);
        assertThat(plan.sourceSessions()).containsExactly("2026-10-13","2026-10-14","2026-10-15");
        // Golden H1 reference exported before M5 reactivation, preserving the exact strict levels.
        var golden=json.readTree(Path.of("../frontend/tests/fixtures/threeCandlesExperimentalH1.json").toFile())
            .path("structure").path("preparation").path("experimental").path("strictCandidates");
        assertThat((com.fasterxml.jackson.databind.JsonNode)json.valueToTree(plan.candidates())).isEqualTo(golden);
        var replay=new ThreeCandleReplay("WIN$N",5,bars,opening,plan);
        var live=new CandlePipeline("WIN$N",5);
        bars.stream().filter(c->c.availableAtMsc()<=opening).forEach(live::accept); live.prepare(plan);
        assertThat(replay.pipeline().structure(1).levels()).noneMatch(l->l.timeframeMinutes()==5);
        var today=bars.stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==day).toList();
        var expected=new LinkedHashSet<String>(); var previous=new HashSet<String>();
        var report=new ArrayList<Map<String,Object>>();
        Path out=Path.of("target/threecandles-m5-validation"); Files.createDirectories(out);
        for(int i=0;i<today.size();i++) {
            var update=today.get(i); replay.next(); live.accept(update);
            if(i>=4) {
                var center=today.get(i-2).candle();
                for(var type:StructuralLevel.Type.values()) {
                    boolean extreme=true;
                    for(int j=i-4;j<=i;j++) if(j!=i-2) {
                        var other=today.get(j).candle();
                        extreme &= type==StructuralLevel.Type.TOP ? center.high()>other.high() : center.low()<other.low();
                    }
                    if(extreme) expected.add("WIN$N:5:"+center.bucketStartTimeMsc()+":"+type);
                }
            }
            var snapshot=replay.pipeline().structure(1);
            assertThat(snapshot.confirmedPivots()).allMatch(l -> l.confirmedAtMsc()<=update.availableAtMsc());
            assertThat(snapshot.confirmedPivots()).extracting(StructuralLevel::id).doesNotHaveDuplicates();
            var m5=snapshot.levels().stream().filter(l->l.timeframeMinutes()==5).toList();
            assertThat(m5).extracting(StructuralLevel::id).containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
            assertThat(m5).allMatch(l->l.confirmedAtMsc()<=update.availableAtMsc() && l.extremeTimeMsc()/86400000L==day);
            assertThat(m5).extracting(StructuralLevel::id).containsAll(previous);
            previous=new HashSet<>(expected);
            assertThat(snapshot.levels().stream().filter(l->l.timeframeMinutes()==60).toList()).isEqualTo(plan.levels());
            assertThat(snapshot).isEqualTo(live.structure(1));
            if(i==today.size()/2) {
                var memory=live.activeLevels(); live=new CandlePipeline("WIN$N",5); live.rememberLevels(memory);
                for(var known:bars) if(known.availableAtMsc()<=update.availableAtMsc()) live.accept(known);
                live.prepare(plan); assertThat(live.structure(1)).isEqualTo(snapshot);
            }
            String clock=Instant.ofEpochMilli(update.availableAtMsc()).atOffset(ZoneOffset.UTC).toLocalTime().toString();
            if(Set.of("09:00","10:00","12:00","16:00","18:00").contains(clock) || i==today.size()-1) {
                String name=clock.replace(":","");
                json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(name+".json").toFile(),Map.of(
                    "id","m5-oct16-validation","symbol","WIN$N","timeframeMinutes",5,"status","PAUSED","speed",1,
                    "availableAtMsc",replay.availableAtMsc(),"candles",replay.candles(),"structure",snapshot));
                report.add(Map.of("clock",clock,"m5",m5.size(),"broken",m5.stream().filter(l->l.state()==StructuralLevel.State.BROKEN).count(),"h1",plan.levels().size()));
            }
        }
        assertThat(expected.size()).isGreaterThan(12);
        replay.reset(); assertThat(replay.pipeline().structure(1).levels()).noneMatch(l->l.timeframeMinutes()==5);
        for(int i=0;i<today.size();i++) replay.next();
        assertThat(replay.pipeline().structure(1)).isEqualTo(live.structure(1));
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("report.json").toFile(),Map.of("symbol","WIN$N","date",date.toString(),
            "closedBars",today.size(),"pivots",expected.size(),"checkpoints",report,"causalPrefixes",true,"liveReplayEquality",true,"h1GoldenUnchanged",true,"resetAndReconnect",true));
    }
}
