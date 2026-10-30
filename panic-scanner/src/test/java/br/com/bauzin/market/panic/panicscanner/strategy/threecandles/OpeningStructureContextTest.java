package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpeningStructureContextTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DATA = Path.of("src/test/resources/threecandles/opening-structure");
    static final Path EXPORT = Path.of("../frontend/src/features/threecandles/validation/opening-structure");
    static long at(String time) { return LocalDate.of(2026,10,30).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli(); }
    static List<CandleUpdate> bars(String date) throws Exception {
        var result = new ArrayList<CandleUpdate>();
        for (var c : JSON.readTree(DATA.resolve("WINZ26-"+date+"-M5.json").toFile())) {
            long t=c.path("time").asLong()*1000;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),
                c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        return result;
    }
    static CandleUpdate bar(long t,String symbol,double open,double high,double low,double close) {
        return new CandleUpdate(new IntrabarCandleSnapshot(symbol,t,open,high,low,close),5,true,t+300000);
    }
    @Test void nativeOctober30EveryPrefixIsCausalAndLegacySnapshotsArePreserved() throws Exception {
        var previous=bars("2026-10-29");var current=bars("2026-10-30");
        var all=new ArrayList<>(previous);all.addAll(current);
        var pipeline=new CandlePipeline("WINZ26",5);previous.forEach(pipeline::accept);
        var stream=new OpeningStructureContext("WINZ26","2026-10-30");stream.advance(previous,at("09:00"));
        var replay=new ThreeCandleReplay("WINZ26",5,all,at("09:00"));
        var legacy=JSON.readTree(DATA.resolve("legacy-prefixes.json").toFile());
        var exports=new ArrayList<Object>();
        int index=0;
        for(var b:current) {
            long cutoff=b.availableAtMsc();pipeline.accept(b);replay.next();
            var structure=pipeline.structure(6);
            var main=DowStructureEngine.analyze("WINZ26",replay.candles(),structure,cutoff);
            var micro=MicroDowEngine.analyze("WINZ26",replay.candles(),structure,cutoff);
            var fifty=DowFiftyEngine.analyze("WINZ26",replay.candles(),main,cutoff);
            stream.advance(List.of(b),cutoff);var context=stream.snapshot();
            assertThat(context).isEqualTo(OpeningStructureContext.analyze("WINZ26","2026-10-30",all,cutoff));
            assertThat(context).isEqualTo(OpeningStructureContext.analyze("WINZ26","2026-10-30",replay.candles(),cutoff));
            assertThat(JSON.<com.fasterxml.jackson.databind.JsonNode>valueToTree(main)).isEqualTo(legacy.get(index).path("dow"));
            assertThat(JSON.<com.fasterxml.jackson.databind.JsonNode>valueToTree(micro)).isEqualTo(legacy.get(index).path("micro"));
            assertThat(main).isEqualTo(DowStructureEngine.analyze("WINZ26",replay.candles(),structure,cutoff));
            assertThat(fifty).isEqualTo(DowFiftyEngine.analyze("WINZ26",replay.candles(),main,cutoff));
            assertThat(context.legHistory()).allMatch(l->l.availableAtMsc()<=cutoff && l.destination().availableAtMsc()<=cutoff);
            assertThat(context.previousSession().close().price()).isEqualTo(171050);
            assertThat(context.previousSession().high().price()).isEqualTo(173805);
            assertThat(context.previousSession().low().price()).isEqualTo(170775);
            assertThat(context.previousSession().officialSettlement()).isNull();
            assertThat(context.tradedOpen().price()).isEqualTo(170910);
            assertThat(context.gap().kind()).isEqualTo("GAP");assertThat(context.gap().points()).isEqualTo(-140);
            assertThat(context.initialLeg().origin().price()).isEqualTo(171600);
            if(cutoff==at("09:05"))assertThat(context.initialLeg().intrabarOrder()).isEqualTo("UNKNOWN_SAME_M5");
            if(cutoff==at("09:20"))assertThat(context.initialLeg().destination().price()).isEqualTo(170115);
            if(cutoff==at("09:50"))assertThat(context.initialLeg().destination().price()).isEqualTo(169070);
            if(cutoff==at("09:35"))assertThat(micro.movements()).hasSize(1);
            if(cutoff<at("10:15"))assertThat(main.movements()).isEmpty();
            if(cutoff==at("10:15"))assertThat(main.movements()).hasSize(1);
            var view=new LinkedHashMap<String,Object>();
            view.put("id","opening-oct30");view.put("symbol","WINZ26");view.put("timeframeMinutes",5);view.put("status","PAUSED");
            view.put("availableAtMsc",cutoff);view.put("candles",replay.candles());view.put("structure",structure);
            view.put("dow",main);view.put("microDow",micro);view.put("dowFifty",fifty);view.put("openingStructure",context);
            exports.add(view);
            if(Set.of("09:10","09:25","09:35","09:55","10:15","10:30").stream().anyMatch(t->at(t)==cutoff)) {
                Files.createDirectories(EXPORT);String name=Instant.ofEpochMilli(cutoff).atOffset(ZoneOffset.UTC).toLocalTime().toString().replace(":","");
                JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve(name+"-after.json").toFile(),view);
                var before=new LinkedHashMap<>(view);before.remove("openingStructure");
                JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve(name+"-before.json").toFile(),before);
            }
            index++;
        }
        var end=stream.snapshot();
        assertThat(end.windows().stream().map(OpeningStructureContext.Window::amplitudePoints)).containsExactly(1480.,1485.,2070.);
        assertThat(end.windows()).allMatch(w->w.status().equals("COMPLETE"));
        assertThat(end.legHistory()).hasSize(18);
        var before=stream.snapshot();stream.advance(all,at("10:30"));assertThat(stream.snapshot()).isEqualTo(before);
        assertThatThrownBy(()->stream.advance(all,at("09:00"))).isInstanceOf(IllegalArgumentException.class);
        JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve("prefixes.json").toFile(),exports);
    }
    @Test void progressiveNativeM1NeverUpdatesOpeningFromPartialM5AndMatchesClosedReplay() throws Exception {
        var previous=bars("2026-10-29");var official=new ArrayList<>(previous);official.addAll(bars("2026-10-30"));
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-30-M1.json").toFile(),Mt5Candle[].class));
        var updates=ProgressiveM1History.build("WINZ26",official,minutes,at("09:00"));
        var replay=new ThreeCandleReplay("WINZ26",5,updates,at("09:00"),null,ThreeCandleReplay.Mode.PROGRESSIVE_M1);
        int partial=0,closed=0;
        for(var b:updates.stream().filter(u->u.availableAtMsc()>at("09:00")).toList()) {
            replay.next();long cutoff=replay.availableAtMsc();
            var context=OpeningStructureContext.analyze("WINZ26","2026-10-30",replay.candles(),cutoff);
            assertThat(context).isEqualTo(OpeningStructureContext.analyze("WINZ26","2026-10-30",official,cutoff));
            var rebuilt=new ThreeCandleReplay("WINZ26",5,updates,cutoff,null,ThreeCandleReplay.Mode.PROGRESSIVE_M1);
            assertThat(context).isEqualTo(OpeningStructureContext.analyze("WINZ26","2026-10-30",rebuilt.candles(),cutoff));
            if(b.closed())closed++;else partial++;
        }
        assertThat(closed).isEqualTo(18);assertThat(partial).isEqualTo(72);
    }
    @Test void syntheticSessionsCoverGapDirectionsThresholdsDojiMissingDataAndContractIsolation() {
        for(double gap:new double[]{400,-400,0,5}) {
            long opening=at("09:00");
            var previous=bar(opening-86400000,"WINZ26",1000,1100,900,1000);
            var first=bar(opening,"WINZ26",1000+gap,1100+gap,900+gap,950+gap);
            var second=bar(opening+300000,"WINZ26",950+gap,1000+gap,700+gap,750+gap);
            var s=OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(previous,first,second),opening+600000);
            assertThat(s.gap().points()).isEqualTo(gap);assertThat(s.initialLeg().amplitudePoints()).isEqualTo(400);
            assertThat(s.thresholds()).allMatch(OpeningStructureContext.Threshold::reached);
            assertThat(s.thresholds().get(0).firstReachedAtMsc()).isEqualTo(opening+300000);
            assertThat(s.thresholds().get(1).firstReachedAtMsc()).isEqualTo(opening+600000);
            assertThat(s.previousSession().coverage()).contains("NOT_CERTIFIED");
        }
        long t=at("09:00");var doji=bar(t,"WINZ26",1000,1100,900,1000);
        assertThat(OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(doji),t+300000).state()).isEqualTo("WAITING_DIRECTION");
        var up=bar(t+300000,"WINZ26",1000,1200,950,1150);
        var s=OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(doji,up),t+600000);
        assertThat(s.initialLeg().direction()).isEqualTo("UP");assertThat(s.initialLeg().origin().price()).isEqualTo(900);
        assertThat(s.gap()).isNull();assertThat(s.previousSession()).isNull();
        assertThat(OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(up),t+600000).state()).isEqualTo("UNAVAILABLE");
        var wrong=bar(t-86400000,"WINV26",1000,1100,900,1000);
        assertThat(OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(wrong,doji),t+300000).previousSession()).isNull();
        var late=bar(t+600000,"WINZ26",1000,1200,950,1150);
        assertThat(OpeningStructureContext.analyze("WINZ26","2026-10-30",List.of(doji,late),t+2700000).windows().getFirst().status()).isEqualTo("INCOMPLETE");
    }
    @Test void nativeOtherSessionsCoverUpDownAndSmallGapWithoutUsingOtherExpiries() throws Exception {
        for(var example:List.of(new Object[]{"2026-10-21","2026-10-22",-1560.},
                new Object[]{"2026-10-22","2026-10-23",1090.},new Object[]{"2026-10-29","2026-10-30",-140.})) {
            String previous=(String)example[0],date=(String)example[1];double expected=(double)example[2];
            var all=new ArrayList<>(bars(previous));var session=bars(date);all.addAll(session);
            var context=OpeningStructureContext.analyze("WINZ26",date,all,session.getFirst().availableAtMsc());
            assertThat(context.gap().points()).isEqualTo(expected);
            assertThat(context.previousSession().date()).isEqualTo(previous);
            assertThat(context.gap().availableAtMsc()).isEqualTo(session.getFirst().availableAtMsc());
            // "Small" is an experimental comparison, never a detector requirement.
            assertThat(Math.abs(context.gap().points())<250).isEqualTo(date.equals("2026-10-30"));
        }
    }
}
