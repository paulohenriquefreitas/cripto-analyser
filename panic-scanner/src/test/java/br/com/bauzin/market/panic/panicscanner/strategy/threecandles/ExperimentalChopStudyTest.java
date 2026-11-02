package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ProgressiveM1History;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ExperimentalChopStudyTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final long OPEN=Instant.parse("2026-10-30T09:00:00Z").toEpochMilli();
    static final Path DATA=Path.of("src/test/resources/threecandles/pattern-detector"),OUT=Path.of("../frontend/src/features/threecandles/validation/chop");
    static CandleUpdate bar(long t,double o,double h,double l,double c,boolean closed,long known){return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,o,h,l,c),5,closed,known);}
    static List<CandleUpdate> read(Path file)throws Exception{
        var result=new ArrayList<CandleUpdate>();for(var c:JSON.readTree(file.toFile())){long t=c.path("time").asLong()*1000;result.add(bar(t,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble(),true,t+300000));}return result;
    }
    @Test void nativeFormulaMatchesIndependentTrueRangeIncludingGaps(){
        var bars=new ArrayList<CandleUpdate>();for(int i=0;i<35;i++)bars.add(bar(OPEN+i*300000L,100+i*2,106+i*2,98+i*2,103+i*2,true,OPEN+(i+1)*300000L));
        var s=ExperimentalChopStudy.analyze("WINZ26",bars,bars.getLast().availableAtMsc());
        for(var r:s.confirmed().getLast().readings()){
            double tr=0,hi=-1,lo=Double.POSITIVE_INFINITY;
            for(int i=bars.size()-r.period();i<bars.size();i++){var b=bars.get(i).candle();double prev=bars.get(i-1).candle().close();tr+=Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-prev),Math.abs(b.low()-prev)));hi=Math.max(hi,b.high());lo=Math.min(lo,b.low());}
            assertThat(r.value()).isCloseTo(100*Math.log10(tr/(hi-lo))/Math.log10(r.period()),within(1e-9));
        }
        var gap=bar(OPEN+35*300000L,220,230,215,225,true,OPEN+36*300000L);bars.add(gap);
        assertThat(ExperimentalChopStudy.analyze("WINZ26",bars,gap.availableAtMsc()).confirmed().getLast().readings().getFirst().value())
            .isCloseTo(100*Math.log10((6*8.0+59)/(230-156.0))/Math.log10(7),within(1e-9));
    }
    @Test void missingHistoryFlatRangesAndPartialValuesNeverBecomeArtificialConfirmedNumbers(){
        var engine=new ExperimentalChopStudy("WINZ26",true);
        for(int i=0;i<22;i++){engine.advance(bar(OPEN+i*300000L,100,100,100,100,true,OPEN+(i+1)*300000L));var p=engine.snapshot(OPEN+(i+1)*300000L).confirmed().getLast();
            assertThat(p.readings()).allMatch(r->r.value()==null&&r.delta()==null&&r.regime().equals("UNAVAILABLE"));}
        assertThat(engine.snapshot(OPEN+22*300000L).confirmed().getLast().readings()).allMatch(r->r.status().equals("ZERO_RANGE"));
        var partial=bar(OPEN+22*300000L,100,110,95,105,false,OPEN+22*300000L+60000);engine.advance(partial);
        var s=engine.snapshot(partial.availableAtMsc());assertThat(s.provisional().provisional()).isTrue();assertThat(s.confirmed()).hasSize(22);
        assertThatThrownBy(()->engine.snapshot(OPEN)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nonFiniteNativeLogarithmIsUnavailableRatherThanZero(){
        var e=new ExperimentalChopStudy("WINZ26",true);e.advance(bar(OPEN,1e300,1e300,1e300,1e300,true,OPEN+300000));
        for(int i=1;i<=7;i++)e.advance(bar(OPEN+i*300000L,1e-100,2e-100,1e-100,1e-100,true,OPEN+(i+1)*300000L));
        var r=e.snapshot(OPEN+8*300000).confirmed().getLast().readings().getFirst();
        assertThat(r.value()).isNull();assertThat(r.status()).isEqualTo("NON_FINITE");
    }
    @Test void futureCandlesCannotAffectEarlierReadingsAndFinalPartialEqualsClosedValue(){
        var bars=new ArrayList<CandleUpdate>();for(int i=0;i<30;i++)bars.add(bar(OPEN+i*300000L,100+i,110+i,95+i,105+i,true,OPEN+(i+1)*300000L));
        long at=bars.get(24).availableAtMsc();assertThat(ExperimentalChopStudy.analyze("WINZ26",bars,at)).isEqualTo(ExperimentalChopStudy.analyze("WINZ26",bars.subList(0,25),at));
        var e=new ExperimentalChopStudy("WINZ26",false);bars.subList(0,29).forEach(e::advance);var last=bars.getLast();
        e.advance(new CandleUpdate(last.candle(),5,false,last.availableAtMsc()));var partial=e.snapshot(last.availableAtMsc()).provisional();
        e.advance(last);var closed=e.snapshot(last.availableAtMsc()).confirmed().getLast();assertThat(partial.readings()).isEqualTo(closed.readings());assertThat(e.snapshot(last.availableAtMsc()).provisional()).isNull();
    }
    @Test void nativeFullReplayProgressivePrefixesReconstructionAndExportsAgree()throws Exception{
        Files.createDirectories(OUT);var nativeData=Path.of("src/test/resources/threecandles/chop");var all=new ArrayList<>(read(DATA.resolve("WINZ26-warmup-M5.json")));all.addAll(read(nativeData.resolve("WINZ26-2026-10-30-M5.json")));
        var minutes=Arrays.asList(JSON.readValue(nativeData.resolve("WINZ26-2026-10-30-M1.json").toFile(),Mt5Candle[].class));
        var preparation=H1SessionPreparation.prepare("WINZ26",LocalDate.parse("2026-10-30"),OPEN,all);
        var minuteTimes=new HashSet<Long>();minutes.forEach(m->minuteTimes.add(m.time()*1000));
        var reconstructable=all.stream().filter(b->b.availableAtMsc()<=OPEN||minuteTimes.contains(b.candle().bucketStartTimeMsc())&&minuteTimes.contains(b.candle().bucketStartTimeMsc()+240000)).toList();
        // Final auction M5 has only an 18:31 M1. Keep its official closed OHLC, never invent missing minutes.
        var updates=new ArrayList<>(ProgressiveM1History.build("WINZ26",reconstructable,minutes,OPEN));
        all.stream().filter(b->!reconstructable.contains(b)).forEach(updates::add);updates.sort(Comparator.comparingLong(CandleUpdate::availableAtMsc));
        var incremental=new ExperimentalChopStudy("WINZ26",false);var prefix=new TreeMap<Long,CandleUpdate>();
        var csv=new StringBuilder("policy,availability,candle,provisional,open,high,low,close,displacement,priceDirection,sessionHigh,sessionLow,period,chop,delta,slope,regime,status\n");
        var captures=Set.of("09:25","09:50","10:55","11:20","11:27","11:28","11:30","11:45","12:15","12:30","13:30","15:00","16:10","16:30","18:35");int count=0;
        for(var u:updates){incremental.advance(u);prefix.put(u.candle().bucketStartTimeMsc(),u);if(u.availableAtMsc()<=OPEN)continue;count++;
            var s=incremental.snapshot(u.availableAtMsc());var p=u.closed()?s.confirmed().getLast():s.provisional();
            assertThat(s.confirmed()).allMatch(v->v.availableAtMsc()<=u.availableAtMsc());
            if(u.closed()||count%7==0)assertThat(s).isEqualTo(ExperimentalChopStudy.analyze("WINZ26",List.copyOf(prefix.values()),u.availableAtMsc()));
            if(u.closed())assertThat(s).isEqualTo(ExperimentalChopStudy.analyze("WINZ26",all,u.availableAtMsc()));
            append(csv,s.policy(),p);
            String clock=Instant.ofEpochMilli(u.availableAtMsc()).atOffset(ZoneOffset.UTC).toLocalTime().toString();
            if(captures.contains(clock)){
                var known=List.copyOf(prefix.values());var pipeline=new CandlePipeline("WINZ26",5);known.forEach(pipeline::accept);pipeline.prepare(preparation);var structure=pipeline.structure(6);
                var main=DowStructureEngine.analyze("WINZ26",known,structure,u.availableAtMsc());var micro=MicroDowEngine.analyze("WINZ26",known,structure,u.availableAtMsc());
                var pattern=ThreeCandlePatternStudy.analyze("WINZ26","2026-10-30",all,minutes,preparation,u.availableAtMsc());
                var view=new LinkedHashMap<String,Object>();view.put("id","native-chop-study");view.put("symbol","WINZ26");view.put("status","PAUSED");view.put("timeframeMinutes",5);view.put("availableAtMsc",u.availableAtMsc());view.put("replayMode","PROGRESSIVE_M1");view.put("candles",known);view.put("chop",s);
                view.put("structure",structure);view.put("dow",main);view.put("microDow",micro);view.put("dowFifty",DowFiftyEngine.analyze("WINZ26",known,main,u.availableAtMsc()));view.put("microDowFifty",MicroDowFiftyEngine.analyze(micro,u.availableAtMsc()));view.put("openingStructure",OpeningStructureContext.analyze("WINZ26",known,structure,u.availableAtMsc()));view.put("patternAudit",pattern.visual());view.put("patternStudy",pattern);
                JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve(clock.replace(":","")+".json").toFile(),view);
            }
        }
        assertThat(count).isEqualTo(566);assertThat(incremental.snapshot(all.getLast().availableAtMsc()).confirmed()).hasSize(114);
        var reset=new ExperimentalChopStudy("WINZ26",true);for(var b:all){reset.advance(b);if(b.candle().bucketStartTimeMsc()>=OPEN)append(csv,"SESSION_ONLY_N_PLUS_1",reset.snapshot(b.availableAtMsc()).confirmed().getLast());}
        Files.writeString(OUT.resolve("WINZ26-2026-10-30.csv"),csv);
        var other=new StringBuilder(csv.substring(0,csv.indexOf("\n")+1));var continuous=new ExperimentalChopStudy("WINZ26",false);for(var b:all){continuous.advance(b);if(b.candle().bucketStartTimeMsc()<OPEN)append(other,"CONTINUOUS_AVAILABLE_M5_INCLUDES_OVERNIGHT_TR",continuous.snapshot(b.availableAtMsc()).confirmed().getLast());}Files.writeString(OUT.resolve("WINZ26-previous-sessions.csv"),other);
    }
    static void append(StringBuilder csv,String policy,ExperimentalChopStudy.Point p){for(var r:p.readings())csv.append(String.join(",",policy,Instant.ofEpochMilli(p.availableAtMsc()).toString(),Instant.ofEpochMilli(p.candleTimeMsc()).toString(),""+p.provisional(),""+p.open(),""+p.high(),""+p.low(),""+p.close(),p.displacement()==null?"":""+p.displacement(),p.priceDirection(),""+p.observedSessionHigh(),""+p.observedSessionLow(),""+r.period(),r.value()==null?"":""+r.value(),r.delta()==null?"":""+r.delta(),r.slope(),r.regime(),r.status())).append('\n');}
}
