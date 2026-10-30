package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class MicroDowContinuityTest {
    static final Path ROOT=Path.of("../frontend/src/features/threecandles/validation/continuity");
    static final ObjectMapper JSON=new ObjectMapper();
    static List<CandleUpdate> history() throws Exception {
        var data=JSON.readTree(ROOT.resolve("../dow/WINZ26-m5.json").toFile());var bars=new ArrayList<CandleUpdate>();
        for(var c:data.path("candles")) {
            long t=c.path("time").asLong()*1000;
            if(t/86400000L!=LocalDate.of(2026,10,16).toEpochDay()) continue;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        return bars;
    }
    @Test @EnabledIfSystemProperty(named="micro.baseline",matches="true")
    void captureOriginalSelection() throws Exception {
        var bars=history();Files.createDirectories(ROOT);
        var replay=new ThreeCandleReplay("WINZ26",5,bars,bars.getFirst().candle().bucketStartTimeMsc());
        var prefixes=new ArrayList<MicroDowEngine.Snapshot>();
        for(var bar:bars) {
            replay.next();var at=replay.availableAtMsc();var structure=replay.pipeline().structure(6);
            var micro=MicroDowEngine.analyze("WINZ26",replay.candles(),structure,at);prefixes.add(micro);
            if(List.of("11:00","13:00","17:00").contains(Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).toLocalTime().toString())) {
                var hour=Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).getHour();
                JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve(hour+"-before.json").toFile(),Map.of("symbol","WINZ26","timeframeMinutes",5,"status","PAUSED","availableAtMsc",at,"candles",replay.candles(),"structure",structure,"dow",DowStructureEngine.analyze("WINZ26",replay.candles(),structure,at),"microDow",micro));
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("prefixes-before.json").toFile(),prefixes);
        assertThat(prefixes).hasSize(113);
    }

    @Test void historicalSelectionIsContinuousAndEveryKnownPrefixRebuilds() throws Exception {
        var bars=history();var baseline=JSON.readTree(ROOT.resolve("prefixes-before.json").toFile());
        var stream=new MicroDowEngine("WINZ26");var replay=new ThreeCandleReplay("WINZ26",5,bars,bars.getFirst().candle().bucketStartTimeMsc());
        var exports=new ArrayList<Map<String,Object>>();int index=0;
        for(var bar:bars) {
            replay.next();long at=replay.availableAtMsc();stream.advance(List.of(bar),at);
            var rebuilt=new MicroDowEngine("WINZ26");rebuilt.advance(bars,at);var snapshot=stream.snapshot();
            assertThat(snapshot).isEqualTo(rebuilt.snapshot());
            assertThat(snapshot).isEqualTo(MicroDowEngine.analyze("WINZ26",replay.candles(),replay.pipeline().structure(6),at));
            var before=baseline.get(index++);var raw=JSON.<JsonNode>valueToTree(snapshot);
            assertThat(raw.path("events")).isEqualTo(before.path("events"));
            assertThat(snapshot.pivots()).hasSize(before.path("pivots").size());
            for(int n=0;n<snapshot.pivots().size();n++) {
                var actual=raw.path("pivots").get(n).deepCopy();var original=before.path("pivots").get(n).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode)actual).remove("participation");((com.fasterxml.jackson.databind.node.ObjectNode)original).remove("participation");
                assertThat(actual).isEqualTo(original);
            }
            for(int n=1;n<snapshot.movements().size();n++) assertThat(snapshot.movements().get(n-1).toId()).isEqualTo(snapshot.movements().get(n).fromId());
            var byId=new HashMap<String,StructuralLevel>();snapshot.pivots().forEach(p->byId.put(p.extreme().id(),p.extreme()));
            for(var m:snapshot.movements()) {
                var a=byId.get(m.fromId());var b=byId.get(m.toId());
                assertThat(a.extremeTimeMsc()).isLessThan(b.extremeTimeMsc());assertThat(a.type()).isNotEqualTo(b.type());
                assertThat(m.knownAtMsc()).isLessThanOrEqualTo(at);
            }
            assertThat(snapshot.decisions()).hasSize(snapshot.pivots().size()).allMatch(d->d.atMsc()<=at);
            assertThat(snapshot.movementHistory()).doesNotHaveDuplicates();
            var frozen=snapshot;stream.advance(bars,at);assertThat(stream.snapshot()).isEqualTo(frozen);
            if(List.of("11:00","13:00","17:00").contains(Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).toLocalTime().toString())) {
                var hour=Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).getHour();var structure=replay.pipeline().structure(6);
                var main=DowStructureEngine.analyze("WINZ26",replay.candles(),structure,at);
                assertThat(JSON.<JsonNode>valueToTree(main)).isEqualTo(JSON.readTree(ROOT.resolve(hour+"-before.json").toFile()).path("dow"));
                JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve(hour+"-after.json").toFile(),Map.of("symbol","WINZ26","timeframeMinutes",5,"status","PAUSED","availableAtMsc",at,"candles",replay.candles(),"structure",structure,"dow",main,"microDow",snapshot));
            }
            exports.add(Map.of("atMsc",at,"raw",snapshot.pivots().size(),"selected",snapshot.selectedPivotIds().size(),"segments",snapshot.movements().size(),"segmentVersions",snapshot.movementHistory().size(),"decisions",snapshot.decisions()));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("prefixes-after-audit.json").toFile(),exports);
    }
    private StructuralLevel pivot(int i,StructuralLevel.Type type,double price) {
        return new StructuralLevel("WINZ26:5:micro:"+i+":"+type,type,price,i*300000L,(i+2)*300000L,
            new StructuralLevel.Region(price+1,price-1,price,"PIVOT_CANDLE"),"MICRO_1_1");
    }
    @Test void sameSideReplacementKeepsPriorVersionsAndEqualityKeepsEarliestSymmetrically() {
        for(var type:StructuralLevel.Type.values()) {
            double sign=type==StructuralLevel.Type.TOP?1:-1;var opposite=type==StructuralLevel.Type.TOP?StructuralLevel.Type.BOTTOM:StructuralLevel.Type.TOP;
            var z=new MicroZigZag();var a=pivot(1,opposite,100);var b=pivot(2,type,100+20*sign);var c=pivot(3,type,100+30*sign);
            z.accept(a,false);z.accept(b,false);var old=z.movements().getFirst();z.accept(c,false);
            assertThat(z.movements()).hasSize(1);assertThat(z.movements().getFirst().fromId()).isEqualTo(a.id());assertThat(z.movements().getFirst().toId()).isEqualTo(c.id());
            assertThat(z.history()).containsExactly(old,z.movements().getFirst());assertThat(z.participation(b.id())).isEqualTo("SUPERSEDED");
            var equal=pivot(4,type,c.price());z.accept(equal,false);var lesser=pivot(5,type,100+25*sign);z.accept(lesser,false);
            assertThat(z.selectedIds()).containsExactly(a.id(),c.id());
            assertThat(z.decisions()).anyMatch(d->d.action().equals("EQUAL_RETAINED_EARLIEST")).anyMatch(d->d.action().equals("LESS_EXTREME"));
            z.accept(pivot(6,type,100+50*sign),true);z.accept(pivot(7,opposite,100+5*sign),false);
            assertThat(z.movements()).hasSize(2);assertThat(z.movements().getFirst().toId()).isEqualTo(z.movements().getLast().fromId());
            z.accept(pivot(1,type,100),false);assertThat(z.decisions().getLast().action()).isEqualTo("REJECTED_TIME");
        }
    }
}
