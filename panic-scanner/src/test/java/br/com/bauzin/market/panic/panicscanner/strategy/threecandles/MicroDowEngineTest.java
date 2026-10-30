package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MicroDowEngineTest {
    private CandleUpdate bar(int i,double high,double low) {
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",i*300000L,(high+low)/2,high,low,(high+low)/2),5,true,(i+1)*300000L);
    }
    @Test void confirmationAmbiguityEqualityAndSidewaysNoise() {
        var bars=new ArrayList<CandleUpdate>();
        for(int i=0;i<30;i++) bars.add(bar(i,i%2==0?110:105,i%2==0?100:95));
        var e=new MicroDowEngine("WINZ26");e.advance(bars,600000);assertThat(e.snapshot().pivots()).isEmpty();
        e.advance(bars,900000);assertThat(e.snapshot().pivots()).hasSize(1);
        e.advance(bars,9000000);assertThat(e.snapshot().movements()).hasSize(27);
        assertThat(e.snapshot().pivots()).anyMatch(p->p.classification().equals("EQUAL"));
        var before=e.snapshot();e.advance(bars,9000000);assertThat(e.snapshot()).isEqualTo(before);
        assertThatThrownBy(()->e.advance(bars,0)).isInstanceOf(IllegalArgumentException.class);
        var dual=new MicroDowEngine("WINZ26");dual.advance(List.of(bar(0,105,95),bar(1,110,90),bar(2,105,95)),900000);
        assertThat(dual.snapshot().pivots()).hasSize(2);assertThat(dual.snapshot().movements()).isEmpty();
        assertThat(dual.snapshot().events()).allMatch(v->v.kind().equals("AMBIGUOUS_PIVOT"));
    }
    @Test void historicalPrefixesMatchRebuildAndExportDetectorVersusSelectionAudit() throws Exception {
        var json=new ObjectMapper();var root=Path.of("../frontend/src/features/threecandles/validation/dow");
        var data=json.readTree(root.resolve("WINZ26-m5.json").toFile());
        var bars=new ArrayList<CandleUpdate>();
        for(var c:data.path("candles")) {
            long at=c.path("time").asLong()*1000;
            if(at/86400000L!=java.time.LocalDate.of(2026,10,16).toEpochDay()) continue;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",at,c.path("open").asDouble(),c.path("high").asDouble(),
                c.path("low").asDouble(),c.path("close").asDouble()),5,true,at+300000));
        }
        assertThat(bars).isNotEmpty();
        var stream=new MicroDowEngine("WINZ26");var main=new DowStructureEngine("WINZ26");
        // Strict originals are independently obtained with the unchanged shared detector.
        var detector=new br.com.bauzin.market.panic.panicscanner.application.win.strategy.ConfirmedPivotDetector();
        var originals=new ArrayList<StructuralLevel>();
        for(var b:bars) {
            for(var p:detector.onCandleClosed(b.candle(),b.availableAtMsc())) {
                var o=p.origin();var type=StructuralLevel.Type.valueOf(p.type().name());
                originals.add(new StructuralLevel("WINZ26:5:"+o.bucketStartTimeMsc()+":"+type,type,p.price(),o.bucketStartTimeMsc(),p.confirmedAtMsc(),
                    new StructuralLevel.Region(o.high(),o.low(),(o.high()+o.low())/2,"PIVOT_CANDLE"),"PROXIMITY"));
            }
            stream.advance(List.of(b),b.availableAtMsc());
            var rebuilt=new MicroDowEngine("WINZ26");rebuilt.advance(bars,b.availableAtMsc());
            assertThat(stream.snapshot()).isEqualTo(rebuilt.snapshot());
            main.advance(originals,List.of(b),b.availableAtMsc());
            var mainRebuild=new DowStructureEngine("WINZ26");mainRebuild.advance(originals,bars,b.availableAtMsc());
            assertThat(main.snapshot()).isEqualTo(mainRebuild.snapshot());
        }
        var audit=new ArrayList<Map<String,Object>>();
        for(var p:stream.snapshot().pivots()) {
            var original=main.snapshot().pivots().stream().filter(o->o.extreme().type()==p.extreme().type()
                && o.extreme().extremeTimeMsc()==p.extreme().extremeTimeMsc()).findFirst();
            audit.add(Map.of("micro",p,"baselineParticipation",original.map(DowStructureEngine.Pivot::participation).orElse("NOT_CONFIRMED_2_2"),
                "baselineConfirmedAtMsc",original.map(o->o.extreme().confirmedAtMsc()).orElse(-1L)));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("micro-report.json").toFile(),Map.of(
            "source","WINZ26-m5.json / native MT5 export; 2026-10-16; feed civil timestamps without shift",
            "prefixes",bars.size(),"main",main.snapshot(),"micro",stream.snapshot(),"comparison",audit));
    }
}
