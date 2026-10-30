package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.application.win.strategy.ConfirmedPivotDetector;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.time.*;
import java.util.*;

/** Immutable opening plan. Future session candles never participate in preparation. */
public record H1SessionPreparation(String symbol, String sessionDate, long sessionOpenMsc,
        List<String> sourceSessions, boolean sufficient, String criterion,
        List<StructuralLevel> candidates, List<StructuralLevel> levels, Map<String,List<String>> groups,
        H1DetectionMode detectionMode, H1ExperimentalComparison experimental) {
    public static final String CRITERION = "PREOPEN_THREE_SESSIONS_STRICT_PIVOTS_GROUP_5_POINTS";
    public static final String EXPERIMENTAL_CRITERION = "PREOPEN_THREE_SESSIONS_EXPERIMENTAL_1_1_WITH_2_2";
    private static final long HOUR=3_600_000L, DAY=86_400_000L;

    public static H1SessionPreparation prepare(String symbol, LocalDate date, long opening, List<CandleUpdate> source) {
        return prepare(symbol,date,opening,source,H1DetectionMode.STRICT_2_2);
    }
    public static H1SessionPreparation prepare(String symbol, LocalDate date, long opening, List<CandleUpdate> source, H1DetectionMode mode) {
        mode=mode==null ? H1DetectionMode.STRICT_2_2 : mode;
        var byHour = new TreeMap<Long,List<CandleUpdate>>();
        var dates = new TreeSet<LocalDate>();
        long first=Long.MAX_VALUE;
        for(var update:source) {
            var c=update.candle();
            if(!c.symbol().equals(symbol) || update.timeframeMinutes()!=5)
                throw new IllegalArgumentException("Preparation requires one M5 series");
            if(!update.closed() || update.availableAtMsc()>opening || c.bucketStartTimeMsc()/DAY>=date.toEpochDay()) continue;
            first=Math.min(first,c.bucketStartTimeMsc());
            dates.add(LocalDate.ofEpochDay(c.bucketStartTimeMsc()/DAY));
            byHour.computeIfAbsent(c.bucketStartTimeMsc()/HOUR*HOUR,k->new ArrayList<>()).add(update);
        }
        var sessions=dates.descendingSet().stream().limit(3).sorted().toList();
        var detector=new ConfirmedPivotDetector();
        var memory=new LinkedHashMap<String,StructuralLevel>();
        var hourly=new ArrayList<CandleUpdate>();
        int leftWarmup=0;
        for(var entry:byHour.entrySet()) {
            long time=entry.getKey();
            if(time+HOUR>opening || time==first/HOUR*HOUR && first!=time) continue;
            var bars=entry.getValue().stream().sorted(Comparator.comparingLong(v->v.candle().bucketStartTimeMsc())).toList();
            var start=bars.getFirst().candle(); var end=bars.getLast();
            double high=bars.stream().mapToDouble(v->v.candle().high()).max().orElseThrow();
            double low=bars.stream().mapToDouble(v->v.candle().low()).min().orElseThrow();
            var candle=new IntrabarCandleSnapshot(symbol,time,start.open(),high,low,end.candle().close());
            // Historical bars are closed and known before opening, including short final-session hours.
            // Record the nominal H1 close, never the next session's first M5 availability.
            long available=Math.max(time+HOUR,end.availableAtMsc());
            hourly.add(new CandleUpdate(candle,60,true,available));
            if(!sessions.isEmpty() && time/DAY<sessions.getFirst().toEpochDay()) leftWarmup++;
            memory.replaceAll((id,level)->level.state()==StructuralLevel.State.CONFIRMED
                    && ConfirmedPivotDetector.isBroken(level.type()==StructuralLevel.Type.TOP,level.price(),candle.close())
                    ? new StructuralLevel(level.id(),level.type(),level.price(),level.extremeTimeMsc(),level.confirmedAtMsc(),
                        level.region(),level.relevance(),StructuralLevel.State.BROKEN,available,60) : level);
            for(var pivot:detector.onCandleClosed(candle,available)) {
                var origin=pivot.origin();
                var region=new StructuralLevel.Region(origin.high(),origin.low(),(origin.high()+origin.low())/2,"PIVOT_CANDLE");
                String id=symbol+":60:"+origin.bucketStartTimeMsc()+":"+pivot.type();
                memory.put(id,new StructuralLevel(id,StructuralLevel.Type.valueOf(pivot.type().name()),pivot.price(),
                        origin.bucketStartTimeMsc(),pivot.confirmedAtMsc(),region,"STRICT_H1_PREOPEN",StructuralLevel.State.CONFIRMED,null,60));
            }
        }
        var candidates=memory.values().stream().filter(l->sessions.contains(LocalDate.ofEpochDay(l.extremeTimeMsc()/DAY)))
                .sorted(Comparator.comparingLong(StructuralLevel::extremeTimeMsc).thenComparing(StructuralLevel::id)).toList();
        var selected=new ArrayList<StructuralLevel>();
        var groups=new LinkedHashMap<String,List<String>>();
        double tolerance=symbol.startsWith("WIN")?5:0;
        // Earliest confirmed origin is the stable visual representative. No record is deleted.
        for(var level:candidates) {
            var representative=selected.stream().filter(l->Math.abs(l.price()-level.price())<=tolerance).findFirst();
            if(representative.isPresent()) groups.get(representative.get().id()).add(level.id());
            else {selected.add(level);groups.put(level.id(),new ArrayList<>(List.of(level.id())));}
        }
        H1ExperimentalComparison comparison=null;
        if(mode==H1DetectionMode.EXPERIMENTAL_1_1) {
            comparison=H1ExperimentalComparison.analyze(hourly,candidates,sessions.stream().map(LocalDate::toEpochDay).toList(),opening);
            var all=new ArrayList<>(candidates); all.addAll(comparison.additionalCandidates());
            candidates=List.copyOf(all);
            for(var level:comparison.additionalCandidates()) {
                // Keep all additional origins in the audit; only exact price overlays share a line.
                var representative=selected.stream().filter(l->l.price()==level.price()).findFirst();
                if(representative.isPresent()) groups.get(representative.get().id()).add(level.id());
                else { selected.add(level); groups.put(level.id(),new ArrayList<>(List.of(level.id()))); }
            }
        }
        var frozenGroups=new LinkedHashMap<String,List<String>>();
        groups.forEach((key,value)->frozenGroups.put(key,List.copyOf(value)));
        return new H1SessionPreparation(symbol,date.toString(),opening,sessions.stream().map(LocalDate::toString).toList(),
                sessions.size()==3 && leftWarmup>=2,mode==H1DetectionMode.STRICT_2_2?CRITERION:EXPERIMENTAL_CRITERION,
                List.copyOf(candidates),List.copyOf(selected),Collections.unmodifiableMap(frozenGroups),mode,comparison);
    }
}
