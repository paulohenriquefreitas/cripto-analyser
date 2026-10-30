package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class MicroDowOpeningTest {
    static final Path ROOT=Path.of("../frontend/src/features/threecandles/validation/opening");
    static final ObjectMapper JSON=new ObjectMapper();
    static List<CandleUpdate> history() throws Exception {
        var raw=Files.readString(ROOT.resolve("WINZ26-2026-10-28.json")).replace("\uFEFF","");
        var result=new ArrayList<CandleUpdate>();
        var source=JSON.readTree(raw);
        for(var c:source) {
            long t=c.path("time").asLong()*1000;
            if(t==source.get(source.size()-1).path("time").asLong()*1000) continue; // Native snapshot may end in a forming bar.
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),
                c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        return result;
    }
    @Test void openingDiagnosis() throws Exception {
        var bars=history();var at=bars.get(19).availableAtMsc();
        var replay=new ThreeCandleReplay("WINZ26",5,bars,at);
        var structure=replay.pipeline().structure(6);
        var main=DowStructureEngine.analyze("WINZ26",replay.candles(),structure,at);
        var micro=MicroDowEngine.analyze("WINZ26",replay.candles(),structure,at);
        assertThat(bars.getFirst().candle().bucketStartTimeMsc()).isEqualTo(1793178000000L);
        assertThat(micro.pivots()).noneMatch(p->p.extreme().extremeTimeMsc()==1793178000000L);
        assertThat(micro.pivots().getFirst().extreme().price()).isEqualTo(177010);
        var before=JSON.readTree(ROOT.resolve("before.json").toFile());
        assertThat(JSON.<com.fasterxml.jackson.databind.JsonNode>valueToTree(main)).isEqualTo(before.path("dow"));
        for(String field:List.of("events"))
            assertThat(JSON.valueToTree(micro).path(field)).isEqualTo(before.path("microDow").path(field));
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("after.json").toFile(),Map.of(
            "symbol","WINZ26","timeframeMinutes",5,"status","PAUSED","availableAtMsc",at,"candles",replay.candles(),
            "structure",structure,"dow",main,"microDow",micro));
    }
    private CandleUpdate bar(int index,double high,double low) {
        long t=1793178000000L+index*300000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,(high+low)/2,high,low,(high+low)/2),5,true,t+300000);
    }
    @Test void openingIsSeparateCausalAndIncrementalEqualsEveryReconstruction() throws Exception {
        var bars=history();var stream=new MicroDowEngine("WINZ26");
        var replay=new ThreeCandleReplay("WINZ26",5,bars,bars.getFirst().candle().bucketStartTimeMsc());
        for(var b:bars) {
            replay.next();var at=replay.availableAtMsc();stream.advance(List.of(b),at);
            var rebuilt=new MicroDowEngine("WINZ26");rebuilt.advance(bars,at);
            assertThat(stream.snapshot()).isEqualTo(rebuilt.snapshot());
            assertThat(MicroDowEngine.analyze("WINZ26",replay.candles(),replay.pipeline().structure(6),at)).isEqualTo(stream.snapshot());
            var o=stream.snapshot().opening();
            assertThat(o.events()).allMatch(e->e.atMsc()<=at);
            assertThat(stream.snapshot().pivots()).noneMatch(p->p.extreme().id().contains("opening"));
            if(at<1793179500000L) assertThat(o.status()).isEqualTo("WAITING");
            else {
                assertThat(o.status()).isEqualTo("CONNECTED");assertThat(o.knownAtMsc()).isEqualTo(1793179500000L);
                assertThat(o.anchors().stream().filter(a->a.id().equals(o.fromId())).findFirst().orElseThrow().price()).isEqualTo(178700);
            }
            var prior=stream.snapshot();stream.advance(bars,at);assertThat(stream.snapshot()).isEqualTo(prior);
        }
        replay.reset();assertThat(MicroDowEngine.analyze("WINZ26",replay.candles(),replay.pipeline().structure(6),replay.availableAtMsc()).opening().status()).isEqualTo("UNAVAILABLE");
    }
    @Test void rejectsFalseOpeningMissingOpeningAndAmbiguousIntrabarOrder() {
        for(var bars:List.of(List.of(bar(0,110,100),bar(1,120,95),bar(2,115,98)),
                List.of(bar(0,110,100),bar(1,109,95),bar(2,112,98)))) {
            var e=new MicroDowEngine("WINZ26");e.advance(bars,bars.getLast().availableAtMsc());
            assertThat(e.snapshot().opening().status()).isIn("WAITING","REJECTED");
        }
        var missing=new MicroDowEngine("WINZ26");missing.advance(List.of(bar(1,110,100),bar(2,109,95),bar(3,108,98)),1793181000000L);
        assertThat(missing.snapshot().opening().status()).isEqualTo("UNAVAILABLE");
    }
    @Test void historicalOpeningUpOnOctober16() throws Exception {
        var data=JSON.readTree(ROOT.resolve("../dow/WINZ26-m5.json").toFile());var bars=new ArrayList<CandleUpdate>();
        for(var c:data.path("candles")) {
            long t=c.path("time").asLong()*1000;
            if(t/86400000L!=java.time.LocalDate.of(2026,10,16).toEpochDay()) continue;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        var e=new MicroDowEngine("WINZ26");e.advance(bars,bars.getLast().availableAtMsc());var o=e.snapshot().opening();
        assertThat(o.status()).isEqualTo("CONNECTED");
        assertThat(o.anchors().stream().filter(a->a.id().equals(o.fromId())).findFirst().orElseThrow().price()).isEqualTo(208075);
        assertThat(o.knownAtMsc()).isGreaterThanOrEqualTo(1792142400000L);
    }
    @Test void symmetricOpeningUpWave() {
        var bars=List.of(bar(0,110,90),bar(1,120,95),bar(2,115,98));var e=new MicroDowEngine("WINZ26");
        e.advance(bars,bars.getLast().availableAtMsc());var o=e.snapshot().opening();
        assertThat(o.status()).isEqualTo("CONNECTED");
        assertThat(o.anchors().stream().filter(a->a.id().equals(o.fromId())).findFirst().orElseThrow().price()).isEqualTo(90);
    }
}
