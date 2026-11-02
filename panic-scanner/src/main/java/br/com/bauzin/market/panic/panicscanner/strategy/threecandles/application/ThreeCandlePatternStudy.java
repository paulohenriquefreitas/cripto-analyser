package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import java.time.LocalDate;
import java.util.*;
import static br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalThreeCandlePatternDetector.*;

/** Isolated causal replay of reference publications, closed M5 and genuine native M1 observations. */
public final class ThreeCandlePatternStudy {
    private static final long M5=300000,DAY=86400000;
    private record Step(long at,Minute minute,CandleUpdate observation) {}
    private final String symbol,day;
    private final CandlePipeline pipeline;
    private final DowStructureEngine main;
    private final MicroDowEngine micro;
    private final DowFiftyEngine fifty;
    private final MicroDowFiftyEngine micro50;
    private final ExperimentalThreeCandlePatternDetector detector;
    private final LinkedHashMap<String,Region> historical=new LinkedHashMap<>();
    private final Map<String,Region> dynamic=new LinkedHashMap<>();
    private final List<CandleUpdate> sessionClosed=new ArrayList<>();
    private final Map<Long,ThreeCandleMarketContext.Snapshot> contexts=new HashMap<>();
    private long available;

    public ThreeCandlePatternStudy(String symbol,String day,List<CandleUpdate> warmup,H1SessionPreparation preparation,Parameters parameters) {
        this.symbol=symbol;this.day=LocalDate.parse(day).toString();available=LocalDate.parse(day).toEpochDay()*DAY+9*3600000L;
        pipeline=new CandlePipeline(symbol,5);main=new DowStructureEngine(symbol);micro=new MicroDowEngine(symbol);
        fifty=new DowFiftyEngine(symbol);micro50=new MicroDowFiftyEngine(symbol);detector=new ExperimentalThreeCandlePatternDetector(symbol,day,parameters);
        warmup.stream().filter(b->b.closed()&&b.timeframeMinutes()==5&&b.candle().symbol().equals(symbol)
            &&b.candle().bucketStartTimeMsc()/DAY<LocalDate.parse(day).toEpochDay()&&b.availableAtMsc()<=available)
            .sorted(Comparator.comparingLong(b->b.candle().bucketStartTimeMsc())).forEach(pipeline::accept);
        if(preparation!=null)pipeline.prepare(preparation);
        publish(available);
    }
    private void point(String id,String source,double price,long known,String provenance,Map<String,Region> into) {
        into.putIfAbsent(id,new Region(id,source,price,price,known,provenance));
    }
    private void publish(long at) {
        var structure=pipeline.structure(6);
        for(var p:pipeline.activeLevels())if(p.timeframeMinutes()==5&&p.confirmedAtMsc()<=at)
            point(p.id(),"HISTORICAL_M5_"+p.type(),p.price(),p.confirmedAtMsc(),"M5 pivot formed "+p.extremeTimeMsc()+"; state at observation "+p.state(),historical);
        for(var p:structure.levels())if(p.timeframeMinutes()==60&&p.confirmedAtMsc()<=at)
            point(p.id(),"H1_"+p.type(),p.price(),p.confirmedAtMsc(),"Available H1 preparation/structure",historical);
        var d=main.snapshot();var m=micro.snapshot();
        var mainEnds=new HashSet<String>();d.movements().forEach(w->{mainEnds.add(w.fromId());mainEnds.add(w.toId());});
        for(var p:d.pivots())if(mainEnds.contains(p.extreme().id()))point("main:"+p.extreme().id(),"DOW_PRINCIPAL_EXTREME",p.extreme().price(),p.extreme().confirmedAtMsc(),p.extreme().id(),historical);
        var microEnds=new HashSet<String>();m.movementHistory().forEach(w->{microEnds.add(w.fromId());microEnds.add(w.toId());});
        for(var p:m.pivots())if(microEnds.contains(p.extreme().id()))point(p.extreme().id(),"DOW_MICRO_EXTREME",p.extreme().price(),p.extreme().confirmedAtMsc(),p.extreme().id(),historical);
        var next=new LinkedHashMap<String,Region>();
        if(!sessionClosed.isEmpty()) {
            var h=sessionClosed.stream().max(Comparator.comparingDouble(b->b.candle().high())).orElseThrow();
            var l=sessionClosed.stream().min(Comparator.comparingDouble(b->b.candle().low())).orElseThrow();
            point(symbol+":sessionHigh:"+h.candle().high(),"SESSION_HIGH_CLOSED_M5",h.candle().high(),h.availableAtMsc(),"High of known closed session M5; formed "+h.candle().bucketStartTimeMsc(),next);
            point(symbol+":sessionLow:"+l.candle().low(),"SESSION_LOW_CLOSED_M5",l.candle().low(),l.availableAtMsc(),"Low of known closed session M5; formed "+l.candle().bucketStartTimeMsc(),next);
        }
        var f=fifty.snapshot();
        for(var r:f.references())if(r.wave().id().equals(f.currentWaveId())&&r.endedAtMsc()==null)
            point("main50:"+r.wave().id(),"PRINCIPAL_FIFTY_CONFIRMED",r.wave().level50(),r.wave().recognizedAtMsc(),r.wave().id(),next);
        if(f.formingWave()!=null) {
            var w=f.formingWave();String id=w.id()+":"+w.level50();
            var old=dynamic.get("forming50:"+id);
            point("forming50:"+id,"PRINCIPAL_FIFTY_FORMING",w.level50(),old==null?at:old.availableAtMsc(),"Price version of experimental forming principal wave; never backdated to original recognition",next);
        }
        var mf=micro50.snapshot();
        for(var r:mf.references())if(r.wave().id().equals(mf.currentWaveId()))
            point("micro50:"+r.wave().id(),"MICRO_FIFTY_CONFIRMED",r.wave().level50(),r.wave().availableAtMsc(),r.wave().id(),next);
        dynamic.clear();dynamic.putAll(next);
    }
    private List<Region> regions(){var r=new ArrayList<>(historical.values());r.addAll(dynamic.values());return r;}
    /** Call with genuine consecutive M1 evidence; never extract a fabricated M1 from cumulative M5. */
    public void advanceMinute(Minute minute,CandleUpdate partial) {
        if(minute.availableAtMsc()<available)throw new IllegalArgumentException("Regressing pattern observation");
        long bucket=partial.candle().bucketStartTimeMsc();
        detector.context(bucket,contexts.computeIfAbsent(bucket,t->ThreeCandleMarketContext.capture(symbol,sessionClosed,main.snapshot(),micro.snapshot(),t)));
        detector.references(regions());detector.onMinute(minute,partial);pipeline.accept(partial);
        var d=main.snapshot();
        var asOf=new DowStructureEngine.Snapshot(d.symbol(),d.timeframeMinutes(),minute.availableAtMsc(),d.state(),d.criterion(),d.pivots(),d.movements(),d.events());
        fifty.advance(asOf,List.of(partial),minute.availableAtMsc());micro50.advance(micro.snapshot(),minute.availableAtMsc());
        available=minute.availableAtMsc();publish(available);
    }
    public void advanceClosed(CandleUpdate bar) {
        if(bar.availableAtMsc()<available)throw new IllegalArgumentException("Regressing M5 close");
        long bucket=bar.candle().bucketStartTimeMsc();
        detector.context(bucket,contexts.computeIfAbsent(bucket,t->ThreeCandleMarketContext.capture(symbol,sessionClosed,main.snapshot(),micro.snapshot(),t)));
        detector.references(regions());detector.onCandle(bar);pipeline.accept(bar);sessionClosed.add(bar);
        var s=pipeline.structure(6);
        main.advance(s.confirmedPivots().stream().filter(p->p.extremeTimeMsc()/DAY==LocalDate.parse(day).toEpochDay()).toList(),List.of(bar),bar.availableAtMsc());
        micro.advance(List.of(bar),bar.availableAtMsc());micro50.advance(micro.snapshot(),bar.availableAtMsc());
        fifty.advance(main.snapshot(),List.of(bar),bar.availableAtMsc());available=bar.availableAtMsc();publish(available);
    }
    public Snapshot snapshot(long cutoff,String m1Status){return detector.snapshot(cutoff,m1Status);}

    public static Snapshot analyze(String symbol,String day,List<CandleUpdate> m5,List<Mt5Candle> nativeM1,
                                   H1SessionPreparation preparation,long cutoff) {
        return analyze(symbol,day,m5,nativeM1,preparation,cutoff,Parameters.win());
    }
    public static Snapshot analyze(String symbol,String day,List<CandleUpdate> m5,List<Mt5Candle> nativeM1,
                                   H1SessionPreparation preparation,long cutoff,Parameters parameters) {
        var study=new ThreeCandlePatternStudy(symbol,day,m5,preparation,parameters);
        long opening=LocalDate.parse(day).toEpochDay()*DAY+9*3600000L;
        var current=m5.stream().filter(b->b.timeframeMinutes()==5&&b.candle().symbol().equals(symbol)
            &&b.candle().bucketStartTimeMsc()>=opening&&b.candle().bucketStartTimeMsc()/DAY==LocalDate.parse(day).toEpochDay()).toList();
        var minutes=new TreeMap<Long,Minute>();
        for(var raw:nativeM1) {
            var minute=Minute.of(raw);
            if(minute.startTimeMsc()>=opening&&minute.startTimeMsc()/DAY==LocalDate.parse(day).toEpochDay()&&minute.availableAtMsc()<=cutoff) {
                var old=minutes.putIfAbsent(minute.startTimeMsc(),minute);
                if(old!=null&&!old.equals(minute))throw new IllegalArgumentException("Conflicting native M1");
            }
        }
        var steps=new ArrayList<Step>();boolean complete=!minutes.isEmpty();
        var buckets=new TreeSet<Long>();current.forEach(b->buckets.add(b.candle().bucketStartTimeMsc()));
        complete&=minutes.keySet().stream().allMatch(t->buckets.contains(t/M5*M5));
        for(long bucket:buckets) {
            if(bucket>cutoff)continue;
            var group=minutes.subMap(bucket,true,bucket+M5,false).values().stream().toList();
            double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;
            if(!group.isEmpty()&&group.getFirst().startTimeMsc()==bucket) {
                double open=group.getFirst().open();
                for(var minute:group) {
                    high=Math.max(high,minute.high());low=Math.min(low,minute.low());
                    var partial=new CandleUpdate(new IntrabarCandleSnapshot(symbol,bucket,open,high,low,minute.close()),5,false,minute.availableAtMsc());
                    steps.add(new Step(minute.availableAtMsc(),minute,partial));
                }
            }
            var closed=current.stream().filter(b->b.closed()&&b.candle().bucketStartTimeMsc()==bucket&&b.availableAtMsc()<=cutoff).findFirst();
            if(closed.isPresent()) {
                complete&=group.size()==5;
                if(group.size()==5) {
                    var expected=closed.get().candle();var first=group.getFirst();var last=group.getLast();
                    if(first.open()!=expected.open()||high!=expected.high()||low!=expected.low()||last.close()!=expected.close())
                        throw new IllegalArgumentException("Native M1 disagrees with closed M5 @ "+bucket);
                }
                steps.add(new Step(closed.get().availableAtMsc(),null,closed.get()));
            }
        }
        steps.sort(Comparator.comparingLong(Step::at).thenComparingInt(s->s.minute()==null?1:0));
        for(var step:steps)if(step.minute()!=null)study.advanceMinute(step.minute(),step.observation());else study.advanceClosed(step.observation());
        return study.snapshot(cutoff,minutes.isEmpty()?"M1_AUSENTE_SEQUENCIA_INDETERMINADA":complete?"NATIVE_M1_COMPLETE_KNOWN_PREFIX":"NATIVE_M1_GAPS_INDETERMINATE");
    }
}
