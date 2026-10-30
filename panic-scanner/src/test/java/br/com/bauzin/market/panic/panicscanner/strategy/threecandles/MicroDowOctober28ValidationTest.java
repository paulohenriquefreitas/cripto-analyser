package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Offline validation over a separately captured native MT5 snapshot; no detector changes. */
class MicroDowOctober28ValidationTest {
    static final Path ROOT=Path.of("../frontend/src/features/threecandles/validation/oct28");
    static final ObjectMapper JSON=new ObjectMapper();
    static List<CandleUpdate> history() throws Exception {
        var source=JSON.readTree(ROOT.resolve("source.json").toFile());
        var raw=JSON.readTree(ROOT.resolve("WINZ26-m5.json").toFile());
        assertThat(raw.path("symbol").asText()).isEqualTo("WINZ26");
        var bars=new ArrayList<CandleUpdate>();
        for(var c:raw.path("candles")) {
            long t=c.path("time").asLong()*1000;
            if(t+300000>source.path("nativeLastTickTime").asLong()*1000)continue;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        assertThat(bars).hasSize(source.path("closedBarCount").asInt());return bars;
    }
    @Test void nativeOctober28PrefixesAreCausalAndExportFullSessionView() throws Exception {
        var bars=history();var stream=new MicroDowEngine("WINZ26");
        var replay=new ThreeCandleReplay("WINZ26",5,bars,bars.getFirst().candle().bucketStartTimeMsc());
        var prefixes=new ArrayList<Map<String,Object>>();
        for(var bar:bars) {
            replay.next();long at=replay.availableAtMsc();stream.advance(List.of(bar),at);
            var structure=replay.pipeline().structure(6);
            var rebuilt=MicroDowEngine.analyze("WINZ26",bars,structure,at);
            assertThat(stream.snapshot()).isEqualTo(rebuilt);
            assertThat(MicroDowEngine.analyze("WINZ26",replay.candles(),structure,at)).isEqualTo(rebuilt);
            assertThat(rebuilt.pivots()).allMatch(p->p.extreme().confirmedAtMsc()<=at);
            assertThat(rebuilt.decisions()).allMatch(d->d.atMsc()<=at);
            assertThat(rebuilt.movements()).allMatch(m->m.knownAtMsc()<=at).doesNotHaveDuplicates();
            for(int i=1;i<rebuilt.movements().size();i++)assertThat(rebuilt.movements().get(i).fromId()).isEqualTo(rebuilt.movements().get(i-1).toId());
            var frozen=stream.snapshot();stream.advance(bars,at);assertThat(stream.snapshot()).isEqualTo(frozen);
            prefixes.add(Map.of("atMsc",at,"micro",rebuilt));
        }
        var structure=replay.pipeline().structure(6);long at=replay.availableAtMsc();
        var view=Map.of("symbol","WINZ26","timeframeMinutes",5,"status","PAUSED","availableAtMsc",at,"candles",replay.candles(),"structure",structure,
            "dow",DowStructureEngine.analyze("WINZ26",replay.candles(),structure,at),"microDow",stream.snapshot());
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("session.json").toFile(),view);
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("prefixes.json").toFile(),prefixes);
        replay.reset();assertThat(replay.candles()).isEmpty();
    }
    @Test void preservesFiftyOneOriginalFractalsInOctober16Regression() throws Exception {
        var bars=MicroDowContinuityTest.history();var s=new MicroDowEngine("WINZ26");s.advance(bars,bars.getLast().availableAtMsc());
        assertThat(s.snapshot().pivots()).hasSize(51);
        var before=JSON.readTree(ROOT.resolve("../continuity/prefixes-before.json").toFile());
        var baseline=before.get(before.size()-1).path("pivots");var actual=JSON.<JsonNode>valueToTree(s.snapshot()).path("pivots");
        for(int i=0;i<51;i++) {
            for(String field:List.of("extreme","classification","comparisonId","comparisonPrice","reason"))
                assertThat(actual.get(i).path(field)).isEqualTo(baseline.get(i).path(field));
        }
    }
}
