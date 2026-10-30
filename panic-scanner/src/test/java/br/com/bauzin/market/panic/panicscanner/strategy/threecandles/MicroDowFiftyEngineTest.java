package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MicroDowFiftyEngineTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path EXPORT=Path.of("../frontend/src/features/threecandles/validation/micro-fifty");
    static final Path DATA=Path.of("src/test/resources/threecandles/opening-structure");
    static long at(String day,String time) {return LocalDate.parse(day).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli();}
    static List<CandleUpdate> bars(String day) throws Exception {
        var result=new ArrayList<CandleUpdate>();
        for(var c:JSON.readTree(DATA.resolve("WINZ26-"+day+"-M5.json").toFile())) {
            long t=c.path("time").asLong()*1000;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),
                c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        return result;
    }
    @Test void nativePrefixesPreserveBothEnginesAndUseOnlyTheCurrentConfirmedMicroWave() throws Exception {
        var report=new ArrayList<Object>();
        for(String day:List.of("2026-10-29","2026-10-30")) {
            var all=bars(day);var replay=new ThreeCandleReplay("WINZ26",5,all,at(day,"09:00"));
            var engine=new MicroDowFiftyEngine("WINZ26");
            var fullPipeline=new CandlePipeline("WINZ26",5);all.forEach(fullPipeline::accept);
            var full=MicroDowEngine.analyze("WINZ26",all,fullPipeline.structure(6),all.getLast().availableAtMsc());
            for(var bar:all) {
                replay.next();long cutoff=replay.availableAtMsc();var structure=replay.pipeline().structure(6);
                var micro=MicroDowEngine.analyze("WINZ26",replay.candles(),structure,cutoff);
                var original=JSON.valueToTree(micro);
                var main=DowStructureEngine.analyze("WINZ26",replay.candles(),structure,cutoff);
                var main50=DowFiftyEngine.analyze("WINZ26",replay.candles(),main,cutoff);
                engine.advance(micro,cutoff);var result=engine.snapshot();
                assertThat(result).isEqualTo(MicroDowFiftyEngine.analyze(micro,cutoff));
                // Supplying the complete future micro history must reconstruct the same known prefix.
                assertThat(result).isEqualTo(MicroDowFiftyEngine.analyze(full,cutoff));
                assertThat(JSON.<JsonNode>valueToTree(micro)).isEqualTo(original);
                assertThat(main50).isEqualTo(DowFiftyEngine.analyze("WINZ26",replay.candles(),main,cutoff));
                assertThat(result.references()).hasSameSizeAs(micro.movementHistory());
                assertThat(result.references()).allMatch(r->r.wave().availableAtMsc()<=cutoff
                    && r.wave().level50()==(r.wave().initial().price()+r.wave().terminal().price())/2);
                if(micro.movements().isEmpty()) assertThat(result.currentWaveId()).isNull();
                else assertThat(result.currentWaveId()).isEqualTo(micro.movements().getLast().id());
                if(day.endsWith("30") && cutoff==at(day,"09:25")) {
                    assertThat(micro.opening().status()).isEqualTo("CONNECTED");
                    assertThat(result.references()).isEmpty(); // Opening reference is not a confirmed wave.
                }
                if(day.endsWith("30") && cutoff==at(day,"09:35"))assertThat(result.references().getLast().wave().level50()).isEqualTo(170312.5);
                if(day.endsWith("30") && cutoff==at(day,"09:55"))assertThat(result.references().getLast().wave().level50()).isEqualTo(169790);
                if(day.endsWith("30") && cutoff==at(day,"10:25")) {
                    assertThat(result.references().getLast().wave().level50()).isEqualTo(169472.5);
                    assertThat(main50.references().getLast().wave().level50()).isEqualTo(169427.5);
                }
                var repeated=engine.snapshot();engine.advance(micro,cutoff);assertThat(engine.snapshot()).isEqualTo(repeated);
                report.add(Map.of("day",day,"availableAtMsc",cutoff,"microDowFifty",result));
            }
        }
        Files.createDirectories(EXPORT);JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve("prefixes.json").toFile(),report);
        for(String name:List.of("29-1600","29-1610","30-1015","30-1025")) {
            var before=JSON.readTree(EXPORT.resolve(name+"-before.json").toFile());
            var micro=JSON.treeToValue(before.path("microDow"),MicroDowEngine.Snapshot.class);
            var after=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)after).set("microDowFifty",JSON.valueToTree(MicroDowFiftyEngine.analyze(micro,before.path("availableAtMsc").asLong())));
            JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve(name+"-after.json").toFile(),after);
        }
    }
    @Test void progressiveNativeM1RebuildsIdenticallyAndNeverAnticipatesConfirmedPivots() throws Exception {
        for(String day:List.of("2026-10-29","2026-10-30")) {
            long end=at(day,day.endsWith("29")?"16:10":"10:30");
            var official=bars(day).stream().filter(b->b.availableAtMsc()<=end).toList();
            Path file=day.endsWith("29")?Path.of("src/test/resources/threecandles/micro-fifty/WINZ26-2026-10-29-M1.json")
                :DATA.resolve("WINZ26-2026-10-30-M1.json");
            var minutes=Arrays.asList(JSON.readValue(file.toFile(),Mt5Candle[].class));
            var updates=ProgressiveM1History.build("WINZ26",official,minutes,at(day,"09:00"));
            var replay=new ThreeCandleReplay("WINZ26",5,updates,at(day,"09:00"),null,ThreeCandleReplay.Mode.PROGRESSIVE_M1);
            var engine=new MicroDowFiftyEngine("WINZ26");
            for(var u:updates) {
                replay.next();long cutoff=replay.availableAtMsc();
                var structure=replay.pipeline().structure(6);
                var micro=MicroDowEngine.analyze("WINZ26",replay.candles(),structure,cutoff);
                engine.advance(micro,cutoff);
                var rebuilt=new ThreeCandleReplay("WINZ26",5,updates,cutoff,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1);
                assertThat(engine.snapshot()).isEqualTo(MicroDowFiftyEngine.analyze(MicroDowEngine.analyze("WINZ26",rebuilt.candles(),rebuilt.pipeline().structure(6),cutoff),cutoff));
                assertThat(engine.snapshot().references()).allMatch(r->r.wave().initial().confirmedAtMsc()<=cutoff && r.wave().terminal().confirmedAtMsc()<=cutoff);
                if(!u.closed())assertThat(engine.snapshot().events()).noneMatch(e->e.atMsc()==cutoff);
            }
        }
    }
    private CandleUpdate bar(int index,double high,double low) {
        long t=at("2026-10-30","09:00")+index*300000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,(high+low)/2,high,low,(high+low)/2),5,true,t+300000);
    }
    @Test void confirmedSameSideExtensionsReplaceTheReferenceWithoutErasingThePreviousWave() {
        var bars=List.of(bar(0,110,100),bar(1,105,90),bar(2,110,100),bar(3,120,105),bar(4,115,103),bar(5,130,103),bar(6,120,104));
        var micro=new MicroDowEngine("WINZ26");var fifty=new MicroDowFiftyEngine("WINZ26");
        for(var b:bars) {micro.advance(List.of(b),b.availableAtMsc());fifty.advance(micro.snapshot(),b.availableAtMsc());}
        var snapshot=fifty.snapshot();assertThat(snapshot.references()).hasSize(2);
        var old=snapshot.references().getFirst();var current=snapshot.references().getLast();
        assertThat(old.wave().level50()).isEqualTo(105);assertThat(current.wave().level50()).isEqualTo(110);
        assertThat(old.supersededAtMsc()).isEqualTo(current.wave().availableAtMsc());
        assertThat(old.supersededByWaveId()).isEqualTo(current.wave().id());
        assertThat(snapshot.events().getLast().kind()).isEqualTo("REFERENCE_REPLACED");
        assertThatThrownBy(()->fifty.advance(micro.snapshot(),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new MicroDowFiftyEngine("WINV26").advance(micro.snapshot(),micro.snapshot().availableAtMsc())).isInstanceOf(IllegalArgumentException.class);
    }
}
