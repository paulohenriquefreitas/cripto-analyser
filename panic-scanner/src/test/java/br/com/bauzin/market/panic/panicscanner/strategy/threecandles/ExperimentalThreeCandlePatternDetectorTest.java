package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalThreeCandlePatternDetector.*;

class ExperimentalThreeCandlePatternDetectorTest {
    static final String DAY="2026-10-30";
    static final long START=LocalDate.parse(DAY).atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA=Path.of("src/test/resources/threecandles/pattern-detector");
    static final Path EXPORT=Path.of("../frontend/src/features/threecandles/validation/pattern-wick-v4/legacy");
    static List<CandleUpdate> readBars(String file) throws Exception {
        var result=new ArrayList<CandleUpdate>();
        for(var c:JSON.readTree(DATA.resolve(file).toFile())) {long t=c.path("time").asLong()*1000;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));}
        return result;
    }
    static Snapshot legacyAnalyze(String symbol,String day,List<CandleUpdate> m5,List<Mt5Candle> m1,H1SessionPreparation prep,long at) {
        return ThreeCandlePatternStudy.analyze(symbol,day,m5,m1,prep,at,new Parameters(0,20,0));
    }
    private CandleUpdate bar(int index,double open,double high,double low,double close){return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START+index*300000L,open,high,low,close),5,true,START+(index+1)*300000L);}
    private ExperimentalThreeCandlePatternDetector detector(double region){return detector(region,region>100?"UP":"DOWN");}
    private ExperimentalThreeCandlePatternDetector detector(double region,String flow) {
        var d=new ExperimentalThreeCandlePatternDetector("WINZ26",DAY,new Parameters(0,20,0));
        d.references(List.of(new Region("known","TEST_REFERENCE",region,region,START-60000,"Synthetic isolated rule test")));
        for(int i=0;i<10;i++)d.context(START+i*300000L,new ThreeCandleMarketContext.Snapshot(flow,"UP","DOWN","EXPLICIT_TEST_CONTEXT",START,null,null));
        return d;
    }
    private void minute(ExperimentalThreeCandlePatternDetector d,int offset,double open,double high,double low,double close,double cumulativeHigh,double cumulativeLow) {
        long t=START+600000L+offset*60000L;
        var m=new Minute(t,open,high,low,close);
        var p=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START+600000,offset==0?open:107,cumulativeHigh,cumulativeLow,close),5,false,m.availableAtMsc());
        d.onMinute(m,p);
    }
    private void buySetup(ExperimentalThreeCandlePatternDetector d) {
        d.onCandle(bar(0,110,115,99,100));d.onCandle(bar(1,108,111,98,102));
    }
    @Test void contactOnlyBecomesC1WhenC2NegatesByCloseWithoutChangingColor() {
        var d=detector(100);d.onCandle(bar(0,110,115,99,100));
        var pending=d.snapshot(START+300000,"SYNTHETIC");
        assertThat(pending.visual().patterns()).isEmpty();assertThat(pending.candidates().getFirst().recognizedAtMsc()).isNull();
        d.onCandle(bar(1,108,111,98,102)); // RED C2, lower low allowed, higher close than C1.
        var result=d.snapshot(START+600000,"SYNTHETIC");var c=result.candidates().getFirst();
        assertThat(c.negationVariants()).containsExactly("FECHAMENTO_NAO_CONTINUA_C1");
        assertThat(c.negationComparedExtreme()).isEqualTo(100);assertThat(c.recognizedAtMsc()).isEqualTo(START+600000);
        assertThat(c.context().flow()).isEqualTo("DOWN");assertThat(c.context().principalState()).isEqualTo("UP");
        assertThat(result.visual().patterns().getFirst().stages()).hasSize(2);
        assertThat(result.visual().patterns().getFirst().stages()).allMatch(stage->stage.availableAtMsc()==START+600000);
    }
    @Test void sameColorSellAndEqualityAreAcceptedButAContinuingCloseIsRejected() {
        var sell=detector(110);sell.onCandle(bar(0,100,110,95,108));sell.onCandle(bar(1,103,112,100,107));
        assertThat(sell.snapshot(START+600000,"SYNTHETIC").candidates().getFirst().state()).isEqualTo("NEGA_CONFIRMADA");
        var equal=detector(100);equal.onCandle(bar(0,110,115,99,100));equal.onCandle(bar(1,105,108,98,100));
        assertThat(equal.snapshot(START+600000,"SYNTHETIC").candidates().getFirst().state()).isEqualTo("NEGA_CONFIRMADA");
        var invalid=detector(100,"UP");invalid.onCandle(bar(0,90,110,85,105));invalid.onCandle(bar(1,100,109,95,108));
        var result=invalid.snapshot(START+600000,"SYNTHETIC");
        assertThat(result.candidates().getFirst().state()).isEqualTo("REJEITADO");assertThat(result.visual().patterns()).isEmpty();
    }
    @Test void thirdCandleWithoutContinuationTestNeverPositionsOrTriggers() {
        var d=detector(100);buySetup(d);
        minute(d,0,107,135,107,130,135,107);minute(d,1,130,140,128,135,140,107);
        var result=d.snapshot(START+720000,"SYNTHETIC");var c=result.candidates().getFirst();
        assertThat(c.trigger().state()).isEqualTo("NAO_POSICIONADO");assertThat(c.trigger().level()).isNull();
        assertThat(c.events()).noneMatch(e->e.kind().equals("REGIAO_TESTADA"));
        assertThat(result.visual().patterns().getFirst().stages()).hasSize(2);
    }
    @Test void testThenReturnTriggersBuyAtFixedInitialReferencePlusTwenty() {
        var d=detector(100);buySetup(d);
        minute(d,0,107,109,104,105,109,104);
        var c=d.snapshot(START+660000,"SYNTHETIC").candidates().getFirst();
        assertThat(c.trigger().level()).isEqualTo(127);assertThat(c.trigger().referencePrice()).isEqualTo(107);
        assertThat(c.trigger().referenceKnownAtMsc()).isEqualTo(START+660000);assertThat(c.trigger().state()).isEqualTo("AGUARDANDO");
        minute(d,1,105,128,104,128,128,104);
        c=d.snapshot(START+720000,"SYNTHETIC").candidates().getFirst();
        assertThat(c.testState()).isEqualTo("REJEICAO_OBSERVADA");assertThat(c.trigger().state()).isEqualTo("ALCANCADO_HIPOTETICO");
        assertThat(c.trigger().level()).isEqualTo(127);assertThat(c.trigger().reachedAtMsc()).isEqualTo(START+720000);
    }
    @Test void symmetricSellUsesInitialReferenceMinusTwentyAndDoesNotRequireC2ColorChange() {
        var d=detector(110);d.onCandle(bar(0,100,110,95,108));d.onCandle(bar(1,103,112,100,107));
        minute(d,0,107,110,105,109,110,105);minute(d,1,109,110,85,86,110,85);
        var c=d.snapshot(START+720000,"SYNTHETIC").candidates().getFirst();
        assertThat(c.trigger().referencePrice()).isEqualTo(107);assertThat(c.trigger().level()).isEqualTo(87);
        assertThat(c.trigger().state()).isEqualTo("ALCANCADO_HIPOTETICO");
    }
    @Test void creatingMinuteCannotExecuteAndMissingOrGapOpeningEvidenceStaysIndeterminate() {
        var d=detector(100);buySetup(d);minute(d,0,107,135,104,130,135,104);
        assertThat(d.snapshot(START+660000,"SYNTHETIC").candidates().getFirst().trigger().reachedAtMsc()).isNull();
        minute(d,1,130,140,128,139,140,104);
        assertThat(d.snapshot(START+720000,"SYNTHETIC").candidates().getFirst().trigger().state()).isEqualTo("INDETERMINADO");
        var gap=detector(100);buySetup(gap);minute(gap,0,107,109,104,105,109,104);minute(gap,2,105,130,104,129,130,104);
        assertThat(gap.snapshot(START+780000,"SYNTHETIC").candidates().getFirst().trigger().state()).isEqualTo("INDETERMINADO");
    }
    @Test void m5OnlyCannotInventTheTestReturnSequenceAndContextMustBePriorAndConsistent() {
        var d=detector(100);buySetup(d);d.onCandle(bar(2,107,135,104,130));
        var result=d.snapshot(START+900000,"NO_M1");
        assertThat(result.candidates().getFirst().state()).isEqualTo("INDETERMINADO");
        assertThat(result.candidates().getFirst().trigger().reachedAtMsc()).isNull();
        assertThat(result.visual().patterns().getFirst().stages()).hasSize(2);
        var unknown=new ExperimentalThreeCandlePatternDetector("WINZ26",DAY,new Parameters(0,20,0));
        unknown.references(List.of(new Region("known","TEST",100,100,START-1,"test")));unknown.onCandle(bar(0,110,115,99,100));
        assertThat(unknown.snapshot(START+300000,"SYNTHETIC").candidates()).isEmpty();
        assertThatThrownBy(()->unknown.context(START,ThreeCandleMarketContext.unknown(START+1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->d.context(START,ThreeCandleMarketContext.unknown(START))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void developingDowLegSurvivesSmallBounceAndRejectsMissingOrFutureStructure() {
        var prior=List.of(bar(0,120,130,110,120),bar(1,120,125,100,105),bar(2,105,115,100,110));
        var emptyMain=new DowStructureEngine("WINZ26").snapshot();
        var emptyMicro=new MicroDowEngine("WINZ26").snapshot();
        long before=START+900000;
        var level=new StructuralLevel("top",StructuralLevel.Type.TOP,130,START,START+600000,
            new StructuralLevel.Region(130,110,120,"PIVOT_CANDLE"),"TEST");
        var pivot=new DowStructureEngine.Pivot(level,"TEST",null,null,"TEST","STRUCTURE");
        var main=new DowStructureEngine.Snapshot("WINZ26",5,before,"UP","TEST",List.of(pivot),List.of(),List.of());
        var context=ThreeCandleMarketContext.capture("WINZ26",prior,main,emptyMicro,before);
        assertThat(context.flow()).isEqualTo("DOWN"); // last two closes rise 105 -> 110, but leg from 130 remains down.
        assertThat(context.source()).isEqualTo("PRINCIPAL_DOW_DEVELOPING_LEG");
        assertThat(context.anchor()).isEqualTo(level);
        assertThat(context.principalState()).isEqualTo("UP");
        assertThat(ThreeCandleMarketContext.capture("WINZ26",prior,emptyMain,emptyMicro,before).flow()).isEqualTo("UNDEFINED");
        assertThat(ThreeCandleMarketContext.capture("WINZ26",prior,main,emptyMicro,before-300000).flow()).isEqualTo("UNDEFINED");
        assertThat(ThreeCandleMarketContext.capture("WINZ26",prior,main,emptyMicro,before+300000).source()).isEqualTo("PRIOR_M5_GAP");
        assertThat(ThreeCandleMarketContext.capture("WINZ26",prior,main,emptyMicro,before+86400000).flow()).isEqualTo("UNDEFINED");
        var bottom=new StructuralLevel("bottom",StructuralLevel.Type.BOTTOM,100,START+300000,START+900000,
            new StructuralLevel.Region(125,100,112.5,"PIVOT_CANDLE"),"TEST");
        var micro=new MicroDowEngine.Snapshot("WINZ26",5,before,"UNDEFINED",emptyMicro.criterion(),
            List.of(new DowStructureEngine.Pivot(bottom,"TEST",null,null,"TEST","STRUCTURE")),List.of(),List.of(),
            emptyMicro.opening(),List.of("bottom"),List.of(),List.of(),List.of());
        var microContext=ThreeCandleMarketContext.capture("WINZ26",prior,main,micro,before);
        assertThat(microContext.flow()).isEqualTo("UP");
        assertThat(microContext.source()).isEqualTo("MICRO_DOW_DEVELOPING_LEG");
    }
    @Test void courseOffsetsRemainExactAndNoOrderIsAnActualBrokerExecution() {
        assertThat(hypotheticalLevel(ThreeCandleVisualAudit.Direction.SELL,136855,20)).isEqualTo(136835);
        assertThat(hypotheticalLevel(ThreeCandleVisualAudit.Direction.BUY,136535,20)).isEqualTo(136555);
    }
    @Test void lateReferenceCannotCreateEarlierContactAndM1EvidenceIsIdempotent() {
        var d=detector(100);
        d.references(List.of(new Region("late","TEST",100,100,START+120000,"Published during C1")));
        var first=new Minute(START,110,115,99,108);
        d.onMinute(first,new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START,110,115,99,108),5,false,first.availableAtMsc()));
        assertThat(d.snapshot(first.availableAtMsc(),"SYNTHETIC").candidates()).isEmpty();
        var next=new Minute(START+120000,108,111,99,101);
        var partial=new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START,110,115,99,101),5,false,next.availableAtMsc());
        d.onMinute(next,partial);var known=d.snapshot(next.availableAtMsc(),"SYNTHETIC");
        assertThat(known.candidates().getFirst().events().getFirst().atMsc()).isEqualTo(START+180000);
        assertThat(known.visual().patterns()).isEmpty();
        d.onMinute(next,partial);assertThat(d.snapshot(next.availableAtMsc(),"SYNTHETIC")).isEqualTo(known);
        assertThatThrownBy(()->d.onMinute(new Minute(next.startTimeMsc(),108,112,99,101),partial)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nativeOctober06RetainsSignalsOutsideM1GapsAndExportsAllDecisionEvidence(@org.junit.jupiter.api.io.TempDir Path cache) throws Exception {
        String date="2026-10-06";
        long opening=LocalDate.parse(date).atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var all=readBars("WINZ26-2026-10-06-M5-with-warmup.json");
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-06-M1.json").toFile(),Mt5Candle[].class));
        var prep=H1SessionPreparation.prepare("WINZ26",LocalDate.parse(date),opening,all);
        long cutoff=all.getLast().availableAtMsc();
        var result=legacyAnalyze("WINZ26",date,all,minutes,prep,cutoff);
        var withoutM1=legacyAnalyze("WINZ26",date,all,List.of(),prep,cutoff);
        assertThat(withoutM1.candidates()).noneMatch(c->c.trigger().reachedAtMsc()!=null);
        assertThat(result.m1Status()).isEqualTo("NATIVE_M1_GAPS_INDETERMINATE");
        assertThat(result.candidates()).anyMatch(c->c.trigger().reachedAtMsc()!=null);
        assertThat(result.candidates()).allMatch(c->c.events().stream().allMatch(e->e.atMsc()<=cutoff));
        for(var c:result.candidates())if(c.trigger().reachedAtMsc()!=null) {
            assertThat(c.trigger().reachedAtMsc()).isGreaterThan(c.trigger().placedAtMsc());
            assertThat(c.events()).anyMatch(e->e.kind().equals("REGIAO_TESTADA")&&e.atMsc()<c.trigger().reachedAtMsc());
        }
        var destination=Path.of("../frontend/src/features/threecandles/validation/pattern-wick-v4/oct06");Files.createDirectories(destination);
        JSON.writerWithDefaultPrettyPrinter().writeValue(destination.resolve("study-after.json").toFile(),result);
        // Exercise the production loader/controller boundary that previously discarded the entire M1 day.
        var mt5=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient.class);
        var rawM5=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-06-M5-with-warmup.json").toFile(),Mt5Candle[].class));
        org.mockito.Mockito.when(mt5.readCandles("WINZ26","M5",LocalDate.parse(date).minusDays(28),LocalDate.parse(date))).thenReturn(rawM5);
        org.mockito.Mockito.when(mt5.readCandles("WINZ26","M1",LocalDate.parse(date),LocalDate.parse(date))).thenReturn(minutes);
        var paths=new br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider(cache+"/{symbol}/{date}.ndjson","unused","unused");
        var history=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistory(paths,JSON);
        var loader=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistoryLoader(history,paths,mt5,JSON);
        var controller=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController(history,
            org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleLive.class),
            new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol("WINZ26"),loader);
        var view=controller.create(new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController.Start(
            "WINZ26",date,"21:30",5,CandleTimeBasis.MT5_WALL_CLOCK,6,null,
            br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay.Mode.CLOSED_CANDLE));
        assertThat(view.patternStudy().m1Status()).isEqualTo("NATIVE_M1_GAPS_INDETERMINATE");
        assertThat(view.patternStudy().candidates()).anyMatch(c->c.trigger().reachedAtMsc()!=null);
        JSON.writerWithDefaultPrettyPrinter().writeValue(destination.resolve("view-after.json").toFile(),view);
        for(String clock:List.of("10:05","10:30")) {
            var prefix=controller.create(new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController.Start(
                "WINZ26",date,clock,5,CandleTimeBasis.MT5_WALL_CLOCK,6,null,
                br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay.Mode.CLOSED_CANDLE));
            assertThat(prefix.patternStudy().candidates()).allMatch(c->c.events().stream().allMatch(e->e.atMsc()<=prefix.availableAtMsc()));
            JSON.writerWithDefaultPrettyPrinter().writeValue(destination.resolve(clock.replace(":","")+".json").toFile(),prefix);
        }


    }
    @Test void nativeOctober30AuditExportsEveryCandidateIncludingRejectedAndHypotheticalTriggerEvidence() throws Exception {
        var warmup=readBars("WINZ26-warmup-M5.json");var all=new ArrayList<>(warmup);all.addAll(readBars("WINZ26-2026-10-30-M5.json"));
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-30-M1.json").toFile(),Mt5Candle[].class));
        var prep=H1SessionPreparation.prepare("WINZ26",LocalDate.parse(DAY),START,all);
        var result=legacyAnalyze("WINZ26",DAY,all,minutes,prep,all.getLast().availableAtMsc());
        assertThat(result.candidates()).isNotEmpty();assertThat(result.candidates()).allMatch(c->c.events().stream().allMatch(e->e.atMsc()<=result.availableAtMsc()));
        assertThat(result.candidates()).allMatch(c->c.trigger().reachedAtMsc()==null||c.trigger().reachedAtMsc()>c.trigger().placedAtMsc());
        Files.createDirectories(EXPORT);JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve("WINZ26-2026-10-30-study.json").toFile(),result);
    }
    @Test void nativeIncrementalReconstructionClosedReplayAndProgressiveM1AgreeWithoutFutureReferences() throws Exception {
        var warmup=readBars("WINZ26-warmup-M5.json");var all=new ArrayList<>(warmup);var current=readBars("WINZ26-2026-10-30-M5.json");all.addAll(current);
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-30-M1.json").toFile(),Mt5Candle[].class));
        var prep=H1SessionPreparation.prepare("WINZ26",LocalDate.parse(DAY),START,all);
        var updates=br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ProgressiveM1History.build("WINZ26",all,minutes,START);
        var progressive=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay("WINZ26",5,updates,START,prep,
            br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay.Mode.PROGRESSIVE_M1);
        var closed=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay("WINZ26",5,all,START,prep);
        var stream=new ThreeCandlePatternStudy("WINZ26",DAY,warmup,prep,new Parameters(0,20,0));
        var minuteByStart=new HashMap<Long,Mt5Candle>();minutes.forEach(m->minuteByStart.put(m.time()*1000,m));
        Set<String> exports=Set.of("09:21","09:26","09:27","11:13","11:20","11:21","11:23","11:40","11:55","13:45","16:10");
        int checked=0,observed=0;
        for(var u:updates.stream().filter(b->b.availableAtMsc()>START).toList()) {
            progressive.next();long at=u.availableAtMsc();var minute=Minute.of(minuteByStart.get(at-60000));
            stream.advanceMinute(minute,new CandleUpdate(u.candle(),5,false,at));if(u.closed()){stream.advanceClosed(u);closed.next();}
            var snapshot=stream.snapshot(at,"NATIVE_M1_COMPLETE_KNOWN_PREFIX");observed++;
            assertThat(snapshot.candidates()).allMatch(c->c.events().stream().allMatch(e->e.atMsc()<=at));
            String clock=Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC).toLocalTime().toString();
            if(u.closed() || exports.contains(clock)) {
                var rebuilt=legacyAnalyze("WINZ26",DAY,all,minutes,prep,at);
                assertThat(snapshot).isEqualTo(rebuilt);checked++;
                // candles() intentionally trims warmup for presentation; retain the same source history as production.
                if(u.closed())assertThat(snapshot).isEqualTo(legacyAnalyze("WINZ26",DAY,closed.pipeline().snapshot(),minutes,prep,at));
            }
            if(exports.contains(clock)) {
                var bars=progressive.candles();var structure=progressive.pipeline().structure(6);
                var main=DowStructureEngine.analyze("WINZ26",bars,structure,at);var micro=MicroDowEngine.analyze("WINZ26",bars,structure,at);
                var view=new LinkedHashMap<String,Object>();view.put("id","native-pattern-study");view.put("symbol","WINZ26");view.put("timeframeMinutes",5);view.put("status","PAUSED");view.put("speed",1);
                view.put("availableAtMsc",at);view.put("replayMode","PROGRESSIVE_M1");view.put("missingM1Times",progressive.missingM1Times());view.put("candles",bars);view.put("structure",structure);
                view.put("dow",main);view.put("microDow",micro);view.put("dowFifty",DowFiftyEngine.analyze("WINZ26",bars,main,at));view.put("microDowFifty",MicroDowFiftyEngine.analyze(micro,at));
                view.put("openingStructure",OpeningStructureContext.analyze("WINZ26",bars,structure,at));view.put("patternAudit",snapshot.visual());view.put("patternStudy",snapshot);
                Files.createDirectories(EXPORT);JSON.writerWithDefaultPrettyPrinter().writeValue(EXPORT.resolve(clock.replace(":","")+".json").toFile(),view);
            }
        }
        assertThat(observed).isEqualTo(430);assertThat(checked).isGreaterThanOrEqualTo(86);
        var noM1=legacyAnalyze("WINZ26",DAY,all,List.of(),prep,all.getLast().availableAtMsc());
        assertThat(noM1.candidates()).noneMatch(c->c.trigger().reachedAtMsc()!=null);
    }
}
