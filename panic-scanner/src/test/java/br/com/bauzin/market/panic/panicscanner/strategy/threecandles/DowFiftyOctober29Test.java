package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class DowFiftyOctober29Test {
    final ObjectMapper json=new ObjectMapper();
    long at(String time){return LocalDate.parse("2026-10-29").atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli();}
    List<CandleUpdate> bars() throws Exception {
        var result=new ArrayList<CandleUpdate>();
        var root=json.readTree(Path.of("src/test/resources/threecandles/dow-fifty-winZ26-2026-10-29.json").toFile());
        for(var b:root.path("candles"))result.add(json.treeToValue(b,CandleUpdate.class));
        return result;
    }
    DowFiftyEngine.Snapshot snapshot(String time) throws Exception {
        long cutoff=at(time);var known=bars().stream().filter(b->b.availableAtMsc()<=cutoff).toList();
        var pipeline=new CandlePipeline("WINZ26",5);known.forEach(pipeline::accept);
        var dow=DowStructureEngine.analyze("WINZ26",known,pipeline.structure(6),cutoff);
        return DowFiftyEngine.analyze("WINZ26",known,dow,cutoff);
    }
    @Test void confirmedReferenceWaitsUntil1610OnOctober29() throws Exception {
        var before=snapshot("15:55");
        assertThat(before.references().getLast().wave().level50()).isEqualTo(171387.5);
        var after=snapshot("16:10");var wave=after.references().getLast().wave();
        assertThat(wave.level50()).isEqualTo(171915);
        assertThat(wave.terminal().extremeTimeMsc()).isEqualTo(at("15:55"));
        assertThat(wave.recognizedAtMsc()).isEqualTo(at("16:10"));
    }
    @Test void formingReferenceFollowsTheNewWaveAtTheFirstObservedBreak() throws Exception {
        assertThat(snapshot("15:50").formingWave()).isNull();
        var first=snapshot("15:55").formingWave();
        assertThat(first).isNotNull();
        assertThat(first.initial().price()).isEqualTo(170985);
        assertThat(first.high()).isEqualTo(172550);
        assertThat(first.level50()).isEqualTo(171767.5);
        assertThat(first.recognizedAtMsc()).isEqualTo(at("15:55"));
        var extended=snapshot("16:00").formingWave();
        assertThat(extended.level50()).isEqualTo(171915);
        assertThat(extended.extremeTimeMsc()).isEqualTo(at("15:55"));
        assertThat(snapshot("16:05").formingWave().level50()).isEqualTo(171915);
        assertThat(snapshot("16:10").formingWave()).isNull();
        assertThat(snapshot("16:10").references().getLast().wave().level50()).isEqualTo(extended.level50());
    }
    @Test void incrementalAndRebuiltPrefixesAgreeAndDoNotUseFutureBars() throws Exception {
        var pipeline=new CandlePipeline("WINZ26",5);var monitor=new DowFiftyEngine("WINZ26");
        var prefix=new ArrayList<CandleUpdate>();
        for(var bar:bars()) {
            long cutoff=bar.availableAtMsc();prefix.add(bar);pipeline.accept(bar);
            var dow=DowStructureEngine.analyze("WINZ26",prefix,pipeline.structure(6),cutoff);
            monitor.advance(dow,List.of(bar),cutoff);
            assertThat(monitor.snapshot()).isEqualTo(DowFiftyEngine.analyze("WINZ26",bars(),dow,cutoff));
        }
    }
    @Test void anObservedFormingCandleCanSwitchTheVisualReferenceWithoutWaitingForItsM5Close() throws Exception {
        long cutoff=at("15:51");
        var known=new ArrayList<>(bars().stream().filter(b->b.availableAtMsc()<=at("15:50")).toList());
        // Synthetic partial observation, explicitly not an inferred intrabar path from the final M5.
        known.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",at("15:50"),171695,172000,171545,171950),5,false,cutoff));
        var pipeline=new CandlePipeline("WINZ26",5);known.forEach(pipeline::accept);
        var dow=DowStructureEngine.analyze("WINZ26",known,pipeline.structure(6),cutoff);
        var state=DowFiftyEngine.analyze("WINZ26",known,dow,cutoff);
        assertThat(state.formingWave().recognizedAtMsc()).isEqualTo(cutoff);
        assertThat(state.formingWave().high()).isEqualTo(172000);
        assertThat(state.formingWave().level50()).isEqualTo(171492.5);
        assertThat(dow.pivots()).noneMatch(p->p.extreme().extremeTimeMsc()==at("15:50"));
    }
    @Test void exportCausalSnapshotsForTheActualFrontendValidation() throws Exception {
        var directory=Path.of("../frontend/src/features/threecandles/validation/dow-fifty-oct29");Files.createDirectories(directory);
        for(var time:List.of("15:50","15:55","16:00","16:05","16:10")) {
            long cutoff=at(time);var known=bars().stream().filter(b->b.availableAtMsc()<=cutoff).toList();
            var pipeline=new CandlePipeline("WINZ26",5);known.forEach(pipeline::accept);var structure=pipeline.structure(6);
            var dow=DowStructureEngine.analyze("WINZ26",known,structure,cutoff);
            var f=DowFiftyEngine.analyze("WINZ26",known,dow,cutoff);
            var view=Map.of("id","oct29-"+time.replace(":",""),"symbol","WINZ26","timeframeMinutes",5,"status","PAUSED",
                "availableAtMsc",cutoff,"candles",known,"structure",structure,"dow",dow,"dowFifty",f,
                "microDow",MicroDowEngine.analyze("WINZ26",known,structure,cutoff));
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(time.replace(":","")+".json").toFile(),view);
        }
    }

}
