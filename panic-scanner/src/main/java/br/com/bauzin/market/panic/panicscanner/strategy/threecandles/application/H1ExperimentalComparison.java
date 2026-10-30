package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Independent strict 1-left/1-right study; never modifies the shared 2+2 detector. */
public record H1ExperimentalComparison(List<StructuralLevel> strictCandidates,
        List<StructuralLevel> oneOneCandidates, List<StructuralLevel> additionalCandidates,
        Map<String,String> baselineMatches, List<Evaluation> evaluations, List<Region> regions,
        double regionWidthPoints, int outsideWindowCandidates) {
    public record Evaluation(long originMsc, StructuralLevel.Type type, double price, String decision,
                             String reason, Long confirmedAtMsc) {}
    public record TestEpisode(long startMsc, long endMsc, long knownAtMsc, int hours,
                              double reactionUpPoints, double reactionDownPoints, long measuredThroughMsc) {}
    public record Region(String id, double low, double high, List<String> pivotIds, List<TestEpisode> tests) {}
    public static final String CRITERION="EXPERIMENTAL_H1_1_1_PREOPEN";
    // Descriptive band, NOT a score/filter or a replacement for original pivot prices.
    public static final double REGION_WIDTH=50;

    public static H1ExperimentalComparison analyze(List<CandleUpdate> input, List<StructuralLevel> baseline,
                                                   List<Long> sessions, long opening) {
        var hours=input.stream().filter(c -> c.closed() && c.timeframeMinutes()==60 && c.availableAtMsc()<=opening)
                .sorted(Comparator.comparingLong(c->c.candle().bucketStartTimeMsc())).toList();
        var candidates=new ArrayList<StructuralLevel>(); var evaluations=new ArrayList<Evaluation>();
        int outside=0;
        for(int i=0;i<hours.size();i++) {
            var current=hours.get(i).candle();
            boolean inWindow=sessions.contains(current.bucketStartTimeMsc()/86400000L);
            for(var type:StructuralLevel.Type.values()) {
                boolean top=type==StructuralLevel.Type.TOP;
                double price=top?current.high():current.low();
                String decision,reason; Long confirmation=null;
                if(i==0 || i+1==hours.size()) {
                    decision="UNCONFIRMED_1_1"; reason="Missing closed left/right H1 before cutoff";
                } else {
                    var left=hours.get(i-1).candle(); var right=hours.get(i+1);
                    boolean strict=top ? price>left.high() && price>right.candle().high()
                            : price<left.low() && price<right.candle().low();
                    if(!strict) {
                        decision="NOT_EXTREME_1_1";
                        reason="Requires strict "+(top?"high >":"low <")+" both neighbors; left="
                                +(top?left.high():left.low())+", right="+(top?right.candle().high():right.candle().low());
                    } else {
                        confirmation=right.availableAtMsc(); decision="CONFIRMED_1_1";
                        reason="Strict 1-left/1-right, available after right H1 close";
                        if(inWindow) {
                            var region=new StructuralLevel.Region(current.high(),current.low(),(current.high()+current.low())/2,"PIVOT_CANDLE");
                            candidates.add(new StructuralLevel(current.symbol()+":60:"+current.bucketStartTimeMsc()+":"+type+":1+1",
                                    type,price,current.bucketStartTimeMsc(),confirmation,region,CRITERION,StructuralLevel.State.CONFIRMED,null,60));
                        } else outside++;
                    }
                }
                if(inWindow) evaluations.add(new Evaluation(current.bucketStartTimeMsc(),type,price,decision,reason,confirmation));
            }
        }
        // Breaking a level never hides it. Only already closed preopen H1 participates.
        candidates.replaceAll(level -> {
            for(var hour:hours) if(hour.availableAtMsc()>level.confirmedAtMsc()
                    && (level.type()==StructuralLevel.Type.TOP ? hour.candle().close()>level.price() : hour.candle().close()<level.price()))
                return new StructuralLevel(level.id(),level.type(),level.price(),level.extremeTimeMsc(),level.confirmedAtMsc(),
                        level.region(),level.relevance(),StructuralLevel.State.BROKEN,hour.availableAtMsc(),60);
            return level;
        });
        var matches=new LinkedHashMap<String,String>();
        for(var candidate:candidates) baseline.stream().filter(l->l.type()==candidate.type()
                && l.extremeTimeMsc()==candidate.extremeTimeMsc() && l.price()==candidate.price()).findFirst()
                .ifPresent(l->matches.put(candidate.id(),l.id()));
        var additional=candidates.stream().filter(l->!matches.containsKey(l.id())).toList();
        var distinct=new ArrayList<>(baseline); distinct.addAll(additional);
        var regions=regions(distinct,hours,sessions);
        return new H1ExperimentalComparison(List.copyOf(baseline),List.copyOf(candidates),additional,
                Collections.unmodifiableMap(matches),List.copyOf(evaluations),regions,REGION_WIDTH,outside);
    }
    private static List<Region> regions(List<StructuralLevel> pivots,List<CandleUpdate> hours,List<Long> sessions) {
        var ordered=pivots.stream().sorted(Comparator.comparingDouble(StructuralLevel::price).thenComparing(StructuralLevel::id)).toList();
        var groups=new ArrayList<List<StructuralLevel>>();
        for(var pivot:ordered) {
            if(groups.isEmpty() || pivot.price()-groups.getLast().getFirst().price()>REGION_WIDTH)
                groups.add(new ArrayList<>());
            groups.getLast().add(pivot);
        }
        var result=new ArrayList<Region>();
        for(var group:groups) {
            double low=group.getFirst().price(),high=group.getLast().price();
            // The complete band is known only after ALL of its constituent pivots confirm.
            long available=group.stream().mapToLong(StructuralLevel::confirmedAtMsc).max().orElseThrow();
            var tests=new ArrayList<TestEpisode>();
            // A test must intersect the price band and BEGIN after the region was known.
            // Consecutive H1 intersections count as one episode, not new pivots.
            for(int i=0;i<hours.size();i++) {
                var hour=hours.get(i); var c=hour.candle();
                if(!sessions.contains(c.bucketStartTimeMsc()/86400000L) || c.bucketStartTimeMsc()<available
                        || c.high()<low || c.low()>high) continue;
                long start=c.bucketStartTimeMsc(),day=start/86400000L; int end=i;
                while(end+1<hours.size()) {
                    var next=hours.get(end+1).candle();
                    if(next.bucketStartTimeMsc()/86400000L!=day
                            || next.bucketStartTimeMsc()!=hours.get(end).candle().bucketStartTimeMsc()+3600000L
                            || next.high()<low || next.low()>high) break;
                    end++;
                }
                double up=0,down=0; long measured=hours.get(end).availableAtMsc();
                for(int j=end+1;j<Math.min(hours.size(),end+3);j++) {
                    up=Math.max(up,hours.get(j).candle().high()-high);
                    down=Math.max(down,low-hours.get(j).candle().low()); measured=hours.get(j).availableAtMsc();
                }
                tests.add(new TestEpisode(start,hours.get(end).candle().bucketStartTimeMsc(),hours.get(end).availableAtMsc(),
                        end-i+1,up,down,measured));
                i=end;
            }
            result.add(new Region(group.getFirst().id()+":region",low,high,group.stream().map(StructuralLevel::id).toList(),List.copyOf(tests)));
        }
        return List.copyOf(result);
    }
}
