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

class DowStructureEngineTest {
    private static final long M5=300000;
    private StructuralLevel pivot(String symbol,int i,StructuralLevel.Type type,double price) {
        return new StructuralLevel(symbol+":5:"+(i*M5)+":"+type,type,price,i*M5,(i+3)*M5,
            new StructuralLevel.Region(price+1,price-1,price,"PIVOT_CANDLE"),"PROXIMITY");
    }
    @Test void pairedExtremesConfirmTrendAndCorrectionDoesNotReverseIt() {
        for(String symbol:List.of("WIN$N","WINZ26")) {
            var e=new DowStructureEngine(symbol);
            var bottom=StructuralLevel.Type.BOTTOM;var top=StructuralLevel.Type.TOP;
            var p=List.of(pivot(symbol,1,bottom,100),pivot(symbol,3,top,200),pivot(symbol,5,bottom,120),pivot(symbol,7,top,220),
                pivot(symbol,9,bottom,130),pivot(symbol,11,top,210),pivot(symbol,15,bottom,110));
            e.advance(p,List.of(),9*M5);assertThat(e.snapshot().state()).isEqualTo("UNDEFINED");
            e.advance(p,List.of(),10*M5);assertThat(e.snapshot().state()).isEqualTo("UP");
            e.advance(p,List.of(),14*M5);assertThat(e.snapshot().state()).isEqualTo("UP");
            assertThat(e.snapshot().events()).anyMatch(v->v.kind().equals("POSSIBLE_REVERSAL")&&v.stateAfter().equals("UP"));
            var wick=new CandleUpdate(new IntrabarCandleSnapshot(symbol,14*M5,150,160,125,150),5,true,15*M5);
            e.advance(p,List.of(wick),15*M5);assertThat(e.snapshot().state()).isEqualTo("UP");
            var broken=new CandleUpdate(new IntrabarCandleSnapshot(symbol,15*M5,150,160,120,125),5,true,16*M5);
            e.advance(p,List.of(wick,broken),16*M5);assertThat(e.snapshot().state()).isEqualTo("TRANSITION");
            assertThat(e.snapshot().events()).anyMatch(v->v.kind().equals("SUPPORT_BROKEN")&&v.atMsc()==16*M5);
            e.advance(p,List.of(wick,broken),18*M5);assertThat(e.snapshot().state()).isEqualTo("DOWN");
            assertThat(e.snapshot().pivots()).allMatch(v->v.extreme().confirmedAtMsc()<=e.snapshot().availableAtMsc());
        }
    }
    @Test void sameSideLocalsExtensionsAndDualExtremesRemainAuditableWithoutArbitraryIntrabarOrder() {
        var t=StructuralLevel.Type.TOP;var b=StructuralLevel.Type.BOTTOM;
        var p=List.of(pivot("WIN$N",1,b,100),pivot("WIN$N",3,t,200),pivot("WIN$N",5,t,190),pivot("WIN$N",7,t,220),
            pivot("WIN$N",9,t,230),pivot("WIN$N",9,b,90));
        var e=new DowStructureEngine("WIN$N");e.advance(p,List.of(),20*M5);
        assertThat(e.snapshot().pivots()).extracting(DowStructureEngine.Pivot::participation)
            .containsExactly("STRUCTURE","SUPERSEDED","LOCAL","STRUCTURE","LOCAL","LOCAL");
        assertThat(e.snapshot().movements()).hasSize(1);
        assertThat(e.snapshot().movements().getFirst().toId()).isEqualTo(p.get(3).id());
        assertThat(e.snapshot().state()).isEqualTo("UNDEFINED");
        var before=e.snapshot();e.advance(p,List.of(),20*M5);assertThat(e.snapshot()).isEqualTo(before);
        assertThatThrownBy(()->e.advance(p,List.of(),19*M5)).isInstanceOf(IllegalArgumentException.class);
        var other=new DowStructureEngine("WINZ26");other.advance(p,List.of(),20*M5);assertThat(other.snapshot().pivots()).isEmpty();
    }

    @Test void aNewHigherHighCannotRestoreUpUsingAnAlreadyBrokenHigherLow() {
        var b=StructuralLevel.Type.BOTTOM;var t=StructuralLevel.Type.TOP;
        var p=List.of(pivot("WIN$N",1,b,100),pivot("WIN$N",3,t,200),pivot("WIN$N",5,b,120),pivot("WIN$N",7,t,220),
            pivot("WIN$N",11,t,230));
        var e=new DowStructureEngine("WIN$N");e.advance(p,List.of(),10*M5);assertThat(e.snapshot().state()).isEqualTo("UP");
        var close=new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",13*M5,140,150,110,115),5,true,14*M5);
        e.advance(p,List.of(close),14*M5);
        assertThat(e.snapshot().state()).isEqualTo("TRANSITION");
        assertThat(e.snapshot().events()).anyMatch(v->v.kind().equals("SUPPORT_BROKEN")&&v.affectedMovementId()!=null);
        assertThat(e.snapshot().events()).noneMatch(v->v.kind().equals("TREND_CONFIRMED")&&v.atMsc()==14*M5);
    }

    @Test void bearishCorrectionAndWickDoNotReverseUntilAClosedResistanceBreak() {
        var b=StructuralLevel.Type.BOTTOM;var t=StructuralLevel.Type.TOP;
        var p=List.of(pivot("WINZ26",1,t,200),pivot("WINZ26",3,b,100),pivot("WINZ26",5,t,180),pivot("WINZ26",7,b,80),
            pivot("WINZ26",9,t,170),pivot("WINZ26",11,b,90),pivot("WINZ26",15,t,190));
        var e=new DowStructureEngine("WINZ26");e.advance(p,List.of(),14*M5);
        assertThat(e.snapshot().state()).isEqualTo("DOWN");
        var wick=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",14*M5,160,175,150,165),5,true,15*M5);
        e.advance(p,List.of(wick),15*M5);assertThat(e.snapshot().state()).isEqualTo("DOWN");
        var close=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",15*M5,165,180,160,175),5,true,16*M5);
        e.advance(p,List.of(wick,close),16*M5);assertThat(e.snapshot().state()).isEqualTo("TRANSITION");
        e.advance(p,List.of(wick,close),18*M5);assertThat(e.snapshot().state()).isEqualTo("UP");
    }

    @Test @EnabledIfSystemProperty(named="threecandles.m5Cache",matches=".+")
    void realSourcesHaveIdenticalIncrementalAndRebuiltPrefixesAndExportIndependentEvidence() throws Exception {
        var json=new ObjectMapper();var root=Path.of("../frontend/src/features/threecandles/validation/dow");Files.createDirectories(root);
        var summary=new ArrayList<Map<String,Object>>();
        for(String symbol:List.of("WIN$N","WINZ26")) {
            Path source=symbol.equals("WIN$N") ? Path.of(System.getProperty("threecandles.m5Cache")) : root.resolve("WINZ26-m5.json");
            assertThat(source).exists();
            var data=json.readTree(source.toFile());assertThat(data.path("symbol").asText()).isEqualTo(symbol);
            var bars=new ArrayList<CandleUpdate>();
            for(var c:data.path("candles")) {
                long at=c.path("time").asLong()*1000;
                bars.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol,at,c.path("open").asDouble(),c.path("high").asDouble(),
                    c.path("low").asDouble(),c.path("close").asDouble()),5,true,at+M5));
            }
            var date=LocalDate.of(2026,10,16);long opening=date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
            var preparation=H1SessionPreparation.prepare(symbol,date,opening,bars);
            var replay=new ThreeCandleReplay(symbol,5,bars,opening,preparation);
            var stream=new DowStructureEngine(symbol);var checkpoints=new LinkedHashMap<String,Map<String,Object>>();
            var all=new ArrayList<DowStructureEngine.Event>();int prefixes=0;
            var today=bars.stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==date.toEpochDay()).toList();
            for(var bar:today) {
                replay.next();long at=replay.availableAtMsc();var structure=replay.pipeline().structure(6);
                var sessionBars=replay.candles().stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==date.toEpochDay()).toList();
                stream.advance(structure.confirmedPivots(),sessionBars,at);
                var rebuilt=DowStructureEngine.analyze(symbol,replay.candles(),structure,at);
                assertThat(stream.snapshot()).isEqualTo(rebuilt);
                assertThat(rebuilt.events()).allMatch(e->e.atMsc()<=at);
                assertThat(rebuilt.pivots()).allMatch(p->p.extreme().confirmedAtMsc()<=at);
                for(var m:rebuilt.movements()) {
                    var from=rebuilt.pivots().stream().filter(p->p.extreme().id().equals(m.fromId())).findFirst().orElseThrow().extreme();
                    var to=rebuilt.pivots().stream().filter(p->p.extreme().id().equals(m.toId())).findFirst().orElseThrow().extreme();
                    assertThat(from.extremeTimeMsc()).isLessThan(to.extremeTimeMsc());assertThat(from.type()).isNotEqualTo(to.type());
                }
                var recent=rebuilt.events().stream().skip(all.size()).toList();
                Map<String,Object> view=Map.of("id","dow-"+symbol,"symbol",symbol,"timeframeMinutes",5,"status","PAUSED","speed",1,
                    "availableAtMsc",at,"candles",replay.candles(),"structure",structure,"dow",rebuilt);
                if(rebuilt.state().equals("UP"))checkpoints.putIfAbsent("up",view);
                if(rebuilt.state().equals("DOWN"))checkpoints.putIfAbsent("down",view);
                if(recent.stream().anyMatch(e->e.kind().equals("POSSIBLE_REVERSAL")&&e.stateBefore().equals("UP")&&e.stateAfter().equals("UP")))checkpoints.putIfAbsent("correction",view);
                if(recent.stream().anyMatch(e->e.kind().equals("RESISTANCE_BROKEN")))checkpoints.putIfAbsent("resistance",view);
                if(rebuilt.state().equals("TRANSITION")&&recent.stream().anyMatch(e->e.kind().equals("POSSIBLE_REVERSAL")))checkpoints.putIfAbsent("reversal",view);
                all=new ArrayList<>(rebuilt.events());prefixes++;
            }
            var finalStructure=replay.pipeline().structure(6);
            // Deliberately feed final/future memory to every earlier cutoff; output must stay unchanged.
            for(var entry:checkpoints.values()) {
                long at=(Long)entry.get("availableAtMsc");
                assertThat(DowStructureEngine.analyze(symbol,replay.candles(),finalStructure,at)).isEqualTo(entry.get("dow"));
            }
            assertThat(checkpoints.keySet()).contains("up","down","resistance","reversal");
            for(var entry:checkpoints.entrySet())json.writerWithDefaultPrettyPrinter().writeValue(root.resolve(symbol.replace('$','_')+"-"+entry.getKey()+".json").toFile(),entry.getValue());
            summary.add(Map.of("symbol",symbol,"source",source.toString(),"prefixes",prefixes,"checkpoints",checkpoints.keySet(),"final",stream.snapshot()));
            replay.reset();assertThat(DowStructureEngine.analyze(symbol,replay.candles(),replay.pipeline().structure(6),replay.availableAtMsc()).pivots()).isEmpty();
        }
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(),summary);
    }
}
