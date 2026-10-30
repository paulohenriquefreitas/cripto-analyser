package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class DowFiftyEngineTest {
    static final long START=1793178000000L,M5=300000;
    static final Path ROOT=Path.of("../frontend/src/features/threecandles/validation/dow-fifty");
    static final ObjectMapper JSON=new ObjectMapper();
    StructuralLevel pivot(int origin,int confirmation,StructuralLevel.Type type,double price){
        return new StructuralLevel("WINZ26:5:"+(START+origin*M5)+":"+type,type,price,START+origin*M5,START+confirmation*M5,
            new StructuralLevel.Region(price+1,price-1,price,"PIVOT_CANDLE"),"PROXIMITY");
    }
    CandleUpdate bar(int i,double high,double low,double close){return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START+i*M5,close,high,low,close),5,true,START+(i+1)*M5);}
    @Test void midpointAppearsWithSecondPivotAndTracksCurrentMovementAndExtension(){
        var points=List.of(pivot(0,3,StructuralLevel.Type.TOP,200),pivot(2,5,StructuralLevel.Type.BOTTOM,100),
            pivot(4,7,StructuralLevel.Type.TOP,220),pivot(6,9,StructuralLevel.Type.TOP,240));
        var main=new DowStructureEngine("WINZ26");var monitor=new DowFiftyEngine("WINZ26");
        for(int i=3;i<=9;i++) {
            long at=START+i*M5;
            main.advance(points,List.of(),at);monitor.advance(main.snapshot(),List.of(),at);
            if(i<5)assertThat(monitor.snapshot().currentWaveId()).isNull();
            else {
                var wave=monitor.snapshot().references().getLast().wave();
                assertThat(wave.level50()).isEqualTo(i<7?150:i<9?160:170);
                assertThat(wave.recognizedAtMsc()).isEqualTo(START+(i<7?5:i<9?7:9)*M5);
                assertThat(wave.terminal().id()).isEqualTo(points.get(i<7?1:i<9?2:3).id());
            }
            assertThat(monitor.snapshot()).isEqualTo(DowFiftyEngine.analyze("WINZ26",List.of(),main.snapshot(),at));
        }
        assertThat(monitor.snapshot().references()).hasSize(3);
        assertThat(monitor.snapshot().references().getFirst().state()).isEqualTo("ONDA_ENCERRADA");
    }
    @Test void formingDownWaveAndInvalidationAreSymmetricAndDoNotChangeConfirmedMonitoring() {
        var points=List.of(pivot(0,3,StructuralLevel.Type.BOTTOM,100),pivot(2,5,StructuralLevel.Type.TOP,200));
        var main=new DowStructureEngine("WINZ26");var monitor=new DowFiftyEngine("WINZ26");
        var bars=new ArrayList<CandleUpdate>();bars.add(bar(5,180,90,95));
        main.advance(points,bars,START+6*M5);monitor.advance(main.snapshot(),bars,START+6*M5);
        var forming=monitor.snapshot().formingWave();
        assertThat(forming.direction()).isEqualTo("DOWN");assertThat(forming.level50()).isEqualTo(145);
        assertThat(monitor.snapshot().references().getLast().wave().level50()).isEqualTo(150);
        bars.add(bar(6,185,80,90));main.advance(points,bars,START+7*M5);monitor.advance(main.snapshot(),bars,START+7*M5);
        assertThat(monitor.snapshot().formingWave().level50()).isEqualTo(140);
        bars.add(bar(7,201,79,180));main.advance(points,bars,START+8*M5);monitor.advance(main.snapshot(),bars,START+8*M5);
        assertThat(monitor.snapshot().formingWave()).isNull();
        assertThat(monitor.snapshot().references().getLast().wave().level50()).isEqualTo(150);
    }
    @Test void rejectionRemainsAnUnvalidatedHypothesisAndMicroContractIsRejected(){
        var points=List.of(pivot(0,3,StructuralLevel.Type.BOTTOM,100),pivot(2,5,StructuralLevel.Type.TOP,200));
        var main=new DowStructureEngine("WINZ26");var m=new DowFiftyEngine("WINZ26");
        var bars=new ArrayList<CandleUpdate>();for(int i=0;i<7;i++)bars.add(bar(i,190,160,170));
        main.advance(points,bars,START+7*M5);m.advance(main.snapshot(),bars,START+7*M5);
        bars.add(bar(7,170,149,160));main.advance(points,bars,START+8*M5);m.advance(main.snapshot(),bars,START+8*M5);
        assertThat(m.snapshot().references().getFirst().rejectionStatus()).isEqualTo("CANDIDATE_UNVALIDATED");
        assertThat(m.snapshot().events()).noneMatch(e->e.kind().contains("REJECTION_CONFIRMED"));
        var wrong=new DowStructureEngine.Snapshot("WINZ26",5,START+8*M5,"UNDEFINED","EXPERIMENTAL_MICRO_STRICT_1_1_V1",List.of(),List.of(),List.of());
        assertThatThrownBy(()->new DowFiftyEngine("WINZ26").advance(wrong,bars,START+8*M5)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void returnHypothesesAreSymmetricAndProximityDoesNotChangeTheWave(){
        var points=List.of(pivot(0,3,StructuralLevel.Type.BOTTOM,100),pivot(2,5,StructuralLevel.Type.TOP,200));
        for(boolean topSide:List.of(true,false)) {
            var bars=new ArrayList<CandleUpdate>();for(int i=0;i<7;i++)bars.add(topSide?bar(i,190,160,170):bar(i,140,110,130));
            var main=new DowStructureEngine("WINZ26");main.advance(points,bars,START+7*M5);
            var monitor=new DowFiftyEngine("WINZ26");monitor.advance(main.snapshot(),bars,START+7*M5);
            bars.add(topSide?bar(7,170,149,160):bar(7,151,130,140));main.advance(points,bars,START+8*M5);monitor.advance(main.snapshot(),bars,START+8*M5);
            assertThat(monitor.snapshot().references().getFirst().rejectionStatus()).isEqualTo("CANDIDATE_UNVALIDATED");
            bars.add(topSide?bar(8,200,160,190):bar(8,140,100,110));main.advance(points,bars,START+9*M5);monitor.advance(main.snapshot(),bars,START+9*M5);
            assertThat(monitor.snapshot().references().getFirst().rejectionStatus()).isEqualTo("TARGET_OBSERVED_UNVALIDATED");
            assertThat(monitor.snapshot().events()).anyMatch(e->e.kind().equals("RETURN_DESTINATION_OBSERVED"));
            assertThat(monitor.snapshot().events()).noneMatch(e->e.kind().contains("REJECTION_CONFIRMED"));
        }
        var bars=new ArrayList<CandleUpdate>();for(int i=0;i<7;i++)bars.add(bar(i,190,160,170));
        var main=new DowStructureEngine("WINZ26");main.advance(points,bars,START+7*M5);
        var narrow=new DowFiftyEngine("WINZ26",0);var wide=new DowFiftyEngine("WINZ26",5);
        narrow.advance(main.snapshot(),bars,START+7*M5);wide.advance(main.snapshot(),bars,START+7*M5);
        bars.add(bar(7,158,152,155));main.advance(points,bars,START+8*M5);
        narrow.advance(main.snapshot(),bars,START+8*M5);wide.advance(main.snapshot(),bars,START+8*M5);
        assertThat(narrow.snapshot().events()).noneMatch(e->e.kind().equals("APPROACH"));
        assertThat(wide.snapshot().events()).anyMatch(e->e.kind().equals("APPROACH"));
        assertThat(narrow.snapshot().references().getFirst().wave()).isEqualTo(wide.snapshot().references().getFirst().wave());
    }
    @Test void ambiguousCompletionsDoNotSelectAnyReference(){
        var points=List.of(pivot(0,3,StructuralLevel.Type.BOTTOM,100),pivot(2,5,StructuralLevel.Type.TOP,200));
        var main=new DowStructureEngine("WINZ26");main.advance(points,List.of(),START+7*M5);var s=main.snapshot();
        var events=new ArrayList<>(s.events());var seal=events.stream().filter(e->e.kind().equals("STRUCTURE_PIVOT_SELECTED")&&e.pivotId().equals(points.getLast().id())).findFirst().orElseThrow();
        events.add(new DowStructureEngine.Event("duplicate",seal.atMsc(),seal.kind(),seal.pivotId(),seal.affectedMovementId(),seal.stateBefore(),seal.stateAfter(),seal.reason(),null,null));
        var ambiguous=new DowStructureEngine.Snapshot(s.symbol(),5,s.availableAtMsc(),s.state(),s.criterion(),s.pivots(),s.movements(),events);
        var monitor=new DowFiftyEngine("WINZ26");monitor.advance(ambiguous,List.of(),s.availableAtMsc());
        assertThat(monitor.snapshot().currentWaveId()).isNull();assertThat(monitor.snapshot().references()).isEmpty();
        assertThat(monitor.snapshot().events()).anyMatch(e->e.kind().equals("AMBIGUOUS_COMPLETION"));
    }
    @Test void realSourcesIncrementalReconnectAndReconstructionAreIdenticalAndExportEvidence() throws Exception {
        Files.createDirectories(ROOT);
        for(String symbol:List.of("WINZ26","WIN$N")) {
            var date=LocalDate.parse(symbol.equals("WINZ26")?"2026-10-28":"2026-10-16");
            var raw=JSON.readTree((symbol.equals("WINZ26")?ROOT.resolve("../oct28/WINZ26-m5.json"):ROOT.resolve("WIN_N-2026-10-16.json")).toFile());
            assertThat(raw.path("symbol").asText()).isEqualTo(symbol);
            long cutoff=symbol.equals("WINZ26")?JSON.readTree(ROOT.resolve("../oct28/session.json").toFile()).path("availableAtMsc").asLong():Long.MAX_VALUE;
            var bars=new ArrayList<CandleUpdate>();
            for(var c:raw.path("candles")) {
                long at=c.path("time").asLong()*1000;
                if(at/86400000L!=date.toEpochDay()||at+M5>cutoff)continue;
                bars.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol,at,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,at+M5));
            }
            assertThat(bars).isNotEmpty();var replay=new ThreeCandleReplay(symbol,5,bars,date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli());
            var stream=new DowFiftyEngine(symbol);var prefixes=new ArrayList<Map<String,Object>>();
            for(var bar:bars) {
                replay.next();long at=replay.availableAtMsc();var structure=replay.pipeline().structure(6);
                var dow=DowStructureEngine.analyze(symbol,replay.candles(),structure,at);
                stream.advance(dow,List.of(bar),at);
                assertThat(stream.snapshot()).isEqualTo(DowFiftyEngine.analyze(symbol,bars,dow,at));
                assertThat(stream.snapshot()).isEqualTo(DowFiftyEngine.analyze(symbol,replay.candles(),dow,at));
                assertThat(stream.snapshot().events()).allMatch(e->e.atMsc()<=at);
                assertThat(stream.snapshot().observations()).hasSize(prefixes.size()+1);
                for(var ref:stream.snapshot().references()) {
                    var w=ref.wave();assertThat(w.recognizedAtMsc()).isLessThanOrEqualTo(at);
                    assertThat(w.level50()).isEqualTo((w.high()+w.low())/2);
                    assertThat(w.terminal().confirmedAtMsc()).isEqualTo(w.recognizedAtMsc());
                    assertThat(dow.events()).anyMatch(e->e.id().equals(w.sourceEventId())&&e.atMsc()==w.recognizedAtMsc()&&e.pivotId().equals(w.terminal().id()));
                }
                var frozen=stream.snapshot();stream.advance(dow,bars,at);assertThat(stream.snapshot()).isEqualTo(frozen);
                prefixes.add(Map.of("symbol",symbol,"timeframeMinutes",5,"status","PAUSED","availableAtMsc",at,"candles",replay.candles(),"structure",structure,"dow",dow,"dowFifty",stream.snapshot()));
            }
            assertThat(stream.snapshot().references()).isNotEmpty();
            JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve((symbol.equals("WINZ26")?"WINZ26":"WIN_N")+"-prefixes.json").toFile(),prefixes);
            long heldCut=bars.getLast().availableAtMsc()+86400000L;
            var heldDow=DowStructureEngine.analyze(symbol,bars,replay.pipeline().structure(6),heldCut);
            stream.advance(heldDow,bars,heldCut);
            assertThat(stream.snapshot()).isEqualTo(DowFiftyEngine.analyze(symbol,bars,heldDow,heldCut));
            replay.reset();var dow=DowStructureEngine.analyze(symbol,replay.candles(),replay.pipeline().structure(6),replay.availableAtMsc());
            assertThat(DowFiftyEngine.analyze(symbol,replay.candles(),dow,replay.availableAtMsc()).references()).isEmpty();
        }
    }
}
