package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProgressiveM1ReplayTest {
    static final Path ROOT=Path.of("../frontend/src/features/threecandles/validation/progressive");
    static final ObjectMapper JSON=new ObjectMapper();
    @Test void nativeMinutesMatchOfficialClosesAndNeverRevealFutureDow() throws Exception {
        var summary=new ArrayList<Map<String,Object>>();
        for(var name:List.of("WINZ26","WIN_N")) {
            String symbol=name.equals("WINZ26")?name:"WIN$N";
            var prefixes=JSON.readTree(ROOT.resolve("../dow-fifty/"+name+"-prefixes.json").toFile());
            var finalView=prefixes.get(prefixes.size()-1);
            var official=Arrays.asList(JSON.treeToValue(finalView.get("candles"),CandleUpdate[].class));
            var minutes=Arrays.asList(JSON.readValue(ROOT.resolve(name+"-m1.json").toFile(),Mt5Candle[].class));
            long start=official.getFirst().candle().bucketStartTimeMsc();
            var built=ProgressiveM1History.buildDetailed(symbol,official,minutes,start);
            var updates=built.updates();
            assertThat(updates.stream().filter(CandleUpdate::closed).toList()).isEqualTo(official);
            var replay=new ThreeCandleReplay(symbol,5,updates,start,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1,built.missingM1Times());
            var closed=new ThreeCandleReplay(symbol,5,official,start);
            var stream=new DowFiftyEngine(symbol);
            int closes=0,partials=0;
            for(int i=0;i<updates.size();i++) {
                replay.next(); var update=updates.get(i); long at=replay.availableAtMsc();
                assertThat(replay.candles().getLast()).isEqualTo(update);
                assertThat(replay.candles()).allMatch(c->c.availableAtMsc()<=at);
                var structure=replay.pipeline().structure(6);
                var dow=DowStructureEngine.analyze(symbol,replay.candles(),structure,at);
                stream.advance(dow,replay.candles(),at);
                assertThat(stream.snapshot()).isEqualTo(DowFiftyEngine.analyze(symbol,replay.candles(),dow,at));
                assertThat(dow.events()).allMatch(e->e.atMsc()<=at);
                if(update.closed()) {
                    closed.next(); closes++;
                    assertThat(replay.candles()).isEqualTo(closed.candles());
                    assertThat(structure).isEqualTo(closed.pipeline().structure(6));
                    var expected=DowStructureEngine.analyze(symbol,closed.candles(),closed.pipeline().structure(6),at);
                    assertThat(dow).isEqualTo(expected);
                    assertThat(stream.snapshot()).isEqualTo(DowFiftyEngine.analyze(symbol,closed.candles(),expected,at));
                } else {
                    partials++;
                    // Complete source is available to this test, never to the pipeline.
                    var rebuilt=new ThreeCandleReplay(symbol,5,updates,at,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1,built.missingM1Times());
                    assertThat(rebuilt.candles()).isEqualTo(replay.candles());
                    assertThat(rebuilt.pipeline().structure(6)).isEqualTo(structure);
                }
                String clock=Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).toLocalTime().toString();
                if(Set.of("09:56","09:57","09:58","09:59","10:00").contains(clock)) {
                    var view=new LinkedHashMap<String,Object>();
                    view.put("symbol",symbol);view.put("timeframeMinutes",5);view.put("id","progressive-audit");
                    view.put("status","PAUSED");view.put("speed",1);view.put("availableAtMsc",at);
                    view.put("replayMode","PROGRESSIVE_M1");view.put("missingM1Times",replay.missingM1Times());
                    view.put("candles",replay.candles());view.put("structure",structure);view.put("dow",dow);view.put("dowFifty",stream.snapshot());
                    JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve(name+"-"+clock.replace(":","")+".json").toFile(),view);
                }
            }
            assertThat(replay.status()).isEqualTo(ThreeCandleReplay.Status.COMPLETED);
            assertThat(replay.availableAtMsc()).isEqualTo(finalView.path("availableAtMsc").asLong());
            assertThat(replay.missingM1Times()).hasSize(name.equals("WINZ26")?1:0);
            summary.add(Map.of("symbol",symbol,"officialM5",closes,"partialUpdates",partials,"steps",updates.size(),
                "missingM1Times",replay.missingM1Times(),"cutoff",replay.availableAtMsc(),"allClosesEqual",true,"causalReconstruction",true));
            replay.reset();assertThat(replay.candles()).isEmpty();
            replay.play(0); replay.advance(1_000_000_000L);assertThat(replay.availableAtMsc()).isEqualTo(updates.getFirst().availableAtMsc());
            replay.pause(1_500_000_000L);var paused=replay.candles();replay.advance(100_000_000_000L);assertThat(replay.candles()).isEqualTo(paused);
            replay.speed(5,100_000_000_000L);replay.play(100_000_000_000L);replay.advance(101_000_000_000L);
            assertThat(replay.availableAtMsc()).isEqualTo(updates.get(5).availableAtMsc());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("summary.json").toFile(),summary);
    }
    @Test void retainsOfficialCloseWithMissingMinutesButStillRejectsOfficialDivergence() {
        long t=1793178000000L;
        var minutes=new ArrayList<Mt5Candle>();for(int i=0;i<5;i++)minutes.add(new Mt5Candle(t/1000+i*60,100,110+i,90-i,101+i,1,1));
        var official=List.of(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,100,114,86,105),5,true,t+300000));
        assertThat(ProgressiveM1History.build("WINZ26",official,minutes,t)).hasSize(5);
        assertThat(ProgressiveM1History.build("WINZ26",official,minutes.subList(1,5),t)).containsExactlyElementsOf(official);
        assertThat(ProgressiveM1History.build("WINZ26",official,minutes.subList(0,4),t).getLast()).isEqualTo(official.getFirst());
        var wrong=new ArrayList<>(minutes);wrong.set(2,new Mt5Candle(t/1000+120,100,1000,90,101,1,1));
        assertThatThrownBy(()->ProgressiveM1History.build("WINZ26",official,wrong,t)).hasMessageContaining("diverge");
    }
    @Test void missingOpeningInteriorFinalOrWholeBucketKeepsOnlyProvenPrefixesAndResumes() {
        long t=1793178000000L;
        var first=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,100,110,90,100),5,true,t+300000);
        var next=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t+300000,100,110,90,100),5,true,t+600000);
        for(var absent:List.of(Set.of(0,1),Set.of(2),Set.of(4),Set.of(0,1,2,3,4))) {
            var minutes=new ArrayList<Mt5Candle>();
            for(int i=0;i<10;i++)if(!absent.contains(i))minutes.add(new Mt5Candle(t/1000+i*60,100,110,90,100,1,1));
            var built=ProgressiveM1History.buildDetailed("WINZ26",List.of(first,next),minutes,t);
            assertThat(built.missingM1Times()).containsExactlyElementsOf(absent.stream().sorted().map(i->t+i*60000L).toList());
            int prefix=java.util.Collections.min(absent);
            var early=built.updates().stream().filter(u->u.candle().bucketStartTimeMsc()==t).toList();
            assertThat(early).hasSize(Math.min(prefix,4)+1);assertThat(early.getLast()).isEqualTo(first);
            var replay=new ThreeCandleReplay("WINZ26",5,built.updates(),t,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1,built.missingM1Times());
            while(replay.status()!=ThreeCandleReplay.Status.COMPLETED) {
                replay.next();assertThat(replay.candles()).allMatch(b->b.availableAtMsc()<=replay.availableAtMsc());
                assertThat(replay.missingM1Times()).containsExactlyElementsOf(built.missingM1Times());
            }
            assertThat(replay.candles()).containsExactly(first,next);
            // The real official close must not hide a missing final M1 from source diagnostics.
            if(absent.contains(4))assertThat(replay.missingM1Times()).contains(t+240000);
            replay.reset();assertThat(replay.candles()).isEmpty();
        }
    }

}
