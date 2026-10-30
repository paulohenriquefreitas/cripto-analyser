package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Read-only experimental observer of principal Dow events. No strategy or Fibonacci dependencies. */
public final class DowFiftyEngine {
    public record Wave(String id,String direction,StructuralLevel initial,StructuralLevel terminal,
                       double high,double low,double level50,long recognizedAtMsc,String sourceEventId,String sealingPivotId) {}
    public record Reference(Wave wave,String state,Long endedAtMsc,String lastEvent,String approachSide,
                            String breakStatus,String rejectionStatus) {}
    public record Event(String id,long atMsc,String waveId,String kind,String stateBefore,String stateAfter,
                        Long candleTimeMsc,Double close,String detail) {}
    public record Observation(long atMsc,long candleTimeMsc,String waveId,String state,double close,
                              Double distance50,Double distanceTop,Double distanceBottom,String approachSide,
                              String breakStatus,String rejectionStatus,String reason) {}
    public record FormingWave(String id,String direction,StructuralLevel initial,StructuralLevel brokenReference,
                              double high,double low,double level50,long extremeTimeMsc,long recognizedAtMsc,
                              long availableAtMsc,String criterion) {}
    public record Snapshot(String symbol,int timeframeMinutes,long availableAtMsc,String criterion,
                           double approachTolerancePoints,String breakPolicy,String rejectionPolicy,String state,
                           String currentWaveId,List<Reference> references,List<Event> events,List<Observation> observations,FormingWave formingWave) {}
    private static final String WAIT="AGUARDANDO_ONDA",CONFIRMED="ONDA_CONFIRMADA",WATCH="MONITORANDO_50",
        BREAK="ROMPIMENTO_EM_AVALIACAO",REJECT="REJEICAO_EM_AVALIACAO",ENDED="ONDA_ENCERRADA";
    private static final class Active {
        final Wave wave;String state=CONFIRMED,lastEvent="WAVE_CONFIRMED",breakStatus="NONE",rejectionStatus="NONE";
        Long ended;int side,breakFrom,rejectionFrom;boolean touched,near;
        Active(Wave wave){this.wave=wave;}
        Reference snapshot(){return new Reference(wave,state,ended,lastEvent,sideName(side),breakStatus,rejectionStatus);}
    }
    private record Completion(long at,String eventId,Wave wave,String error) {}
    private final String symbol;
    private final double tolerance;
    private final List<Active> references=new ArrayList<>();
    private final List<Event> events=new ArrayList<>();
    private final List<Observation> observations=new ArrayList<>();
    private final Set<String> consumedEvents=new HashSet<>();
    private Active active;
    private FormingWave formingWave;
    private final NavigableMap<Long,CandleUpdate> observedCandles=new TreeMap<>();
    private long available=-1,lastBar=Long.MIN_VALUE;
    private Double previousClose;
    public DowFiftyEngine(String symbol){this(symbol,5);}
    public DowFiftyEngine(String symbol,double tolerance){
        this.symbol=Objects.requireNonNull(symbol);
        if(!Double.isFinite(tolerance)||tolerance<0)throw new IllegalArgumentException("Invalid independent Dow proximity tolerance");
        this.tolerance=tolerance;
    }
    private boolean principal(StructuralLevel p){
        return p!=null && Double.isFinite(p.price()) && p.price()>0 && p.timeframeMinutes()==5 && p.id().equals(symbol+":5:"+p.extremeTimeMsc()+":"+p.type())
            && p.confirmedAtMsc()>p.extremeTimeMsc();
    }
    private List<Completion> completions(DowStructureEngine.Snapshot dow,long cutoff){
        var points=new HashMap<String,StructuralLevel>();dow.pivots().forEach(p->points.put(p.extreme().id(),p.extreme()));
        var result=new ArrayList<Completion>();
        var chain=new ArrayList<StructuralLevel>();
        for(var e:dow.events()) {
            if((!e.kind().equals("STRUCTURE_PIVOT_SELECTED")&&!e.kind().equals("TERMINAL_EXTENDED"))||e.atMsc()>cutoff)continue;
            var b=points.get(e.pivotId());
            if(b==null)continue;
            if(e.kind().equals("TERMINAL_EXTENDED")&&!chain.isEmpty())chain.removeLast();
            var a=chain.isEmpty()?null:chain.getLast();
            chain.add(b);
            if(a==null||consumedEvents.contains(e.id()))continue;
            boolean valid=principal(a)&&principal(b)&&a.type()!=b.type()
                &&a.extremeTimeMsc()<b.extremeTimeMsc()&&a.confirmedAtMsc()<=e.atMsc()&&b.confirmedAtMsc()==e.atMsc()
                &&(b.type()==StructuralLevel.Type.TOP?b.price()>a.price():b.price()<a.price());
            if(!valid){result.add(new Completion(e.atMsc(),e.id(),null,"Cannot validate confirmed principal movement"));continue;}
            result.add(new Completion(e.atMsc(),e.id(),new Wave(a.id()+"\u2192"+b.id(),b.type()==StructuralLevel.Type.TOP?"UP":"DOWN",a,b,
                Math.max(a.price(),b.price()),Math.min(a.price(),b.price()),(a.price()+b.price())/2,e.atMsc(),e.id(),b.id()),null));
        }
        return result.stream().sorted(Comparator.comparingLong(Completion::at).thenComparing(Completion::eventId)).toList();
    }
    /** Successive known principal snapshots. Reconnection reconstructs from the same closed session prefix. */
    public void advance(DowStructureEngine.Snapshot dow,List<CandleUpdate> candles,long cutoff){
        if(cutoff<available)throw new IllegalArgumentException("Rebuild Dow 50 for a regressing cursor");
        if(!symbol.equals(dow.symbol())||dow.timeframeMinutes()!=5||dow.availableAtMsc()!=cutoff
            ||!dow.criterion().equals("EXPERIMENTAL_PAIRED_PIVOTS_CLOSE_BREAK_V1"))throw new IllegalArgumentException("Principal Dow snapshot required");
        var pending=completions(dow,cutoff);int index=0;
        for(var bar:candles.stream().filter(b->b.closed()&&b.timeframeMinutes()==5&&b.candle().symbol().equals(symbol)
                &&b.availableAtMsc()<=cutoff&&b.candle().bucketStartTimeMsc()>lastBar)
                .sorted(Comparator.comparingLong(CandleUpdate::availableAtMsc).thenComparingLong(b->b.candle().bucketStartTimeMsc())).toList()) {
            if(bar.availableAtMsc()<bar.candle().bucketStartTimeMsc()+300000)throw new IllegalArgumentException("Closed M5 required");
            while(index<pending.size()&&pending.get(index).at()<bar.availableAtMsc()) {
                int end=groupEnd(pending,index);activate(pending.subList(index,end));index=end;
            }
            observe(bar);previousClose=bar.candle().close();lastBar=bar.candle().bucketStartTimeMsc();
            while(index<pending.size()&&pending.get(index).at()==bar.availableAtMsc()) {
                int end=groupEnd(pending,index);activate(pending.subList(index,end));index=end;
            }
        }
        while(index<pending.size()){int end=groupEnd(pending,index);activate(pending.subList(index,end));index=end;}
        for(var bar:candles) if(bar.timeframeMinutes()==5 && bar.candle().symbol().equals(symbol) && bar.availableAtMsc()<=cutoff) {
            long time=bar.candle().bucketStartTimeMsc();
            var previous=observedCandles.get(time);
            if(previous==null || bar.availableAtMsc()>=previous.availableAtMsc())observedCandles.put(time,bar);
        }
        formingWave=forming(dow,cutoff);
        available=cutoff;
    }
    /** Visual projection only. No unconfirmed pivot enters principal Dow or its monitoring events. */
    private FormingWave forming(DowStructureEngine.Snapshot dow,long cutoff) {
        if(dow.movements().isEmpty())return null;
        var movement=dow.movements().getLast();
        var points=new HashMap<String,StructuralLevel>();dow.pivots().forEach(p->points.put(p.extreme().id(),p.extreme()));
        var anchor=points.get(movement.toId());var boundary=points.get(movement.fromId());
        if(!principal(anchor)||!principal(boundary))return null;
        boolean up=anchor.type()==StructuralLevel.Type.BOTTOM;
        double extreme=anchor.price();long extremeTime=anchor.extremeTimeMsc(),recognized=-1;
        for(var bar:observedCandles.tailMap(anchor.confirmedAtMsc(),true).values()) {
            var c=bar.candle();
            if(c.bucketStartTimeMsc()/86400000L!=anchor.extremeTimeMsc()/86400000L)continue;
            // A range through the anchor invalidates this forming hypothesis; intrabar order is unknown.
            if(up?c.low()<anchor.price():c.high()>anchor.price())return null;
            double price=up?c.high():c.low();
            if(up?price>extreme:price<extreme){extreme=price;extremeTime=c.bucketStartTimeMsc();}
            if(recognized<0 && (up?price>boundary.price():price<boundary.price()))recognized=bar.availableAtMsc();
        }
        if(recognized<0)return null;
        double high=Math.max(anchor.price(),extreme),low=Math.min(anchor.price(),extreme);
        return new FormingWave(anchor.id()+":forming:"+(up?"UP":"DOWN"),up?"UP":"DOWN",anchor,boundary,
            high,low,(high+low)/2,extremeTime,recognized,cutoff,"OBSERVED_BREAK_FROM_CONFIRMED_TERMINAL_V1");
    }
    private int groupEnd(List<Completion> list,int start){int end=start+1;while(end<list.size()&&list.get(end).at()==list.get(start).at())end++;return end;}
    private void activate(List<Completion> group){
        long at=group.getFirst().at();group.forEach(c->consumedEvents.add(c.eventId()));
        if(group.size()!=1||group.getFirst().wave()==null){
            retire(at,"Ambiguous or invalid completion; no current target selected");
            emit(null,at,"AMBIGUOUS_COMPLETION",WAIT,WAIT,null,"No fallback selection: "+group);return;
        }
        var wave=group.getFirst().wave();
        if(references.stream().anyMatch(r->r.wave.id().equals(wave.id()))) {
            emit(active,at,"DUPLICATE_COMPLETION_IGNORED",active==null?WAIT:active.state,active==null?WAIT:active.state,null,"Same principal wave cannot reactivate");return;
        }
        retire(at,"New confirmed principal movement "+wave.id()+" supersedes the former reference");
        active=new Active(wave);references.add(active);
        active.side=previousClose==null?0:side(previousClose,wave.level50());
        emit(active,at,"WAVE_CONFIRMED",WAIT,CONFIRMED,null,"CONFIRMED_MOVEMENT_V2; source="+wave.sourceEventId()+"; sealing pivot="+wave.sealingPivotId());
        transition(active,at,"MONITOR_STARTED",WATCH,null,"Only candles opening after recognition may be monitored");
    }
    private void retire(long at,String reason){
        if(active==null)return;
        transition(active,at,"REFERENCE_ENDED",ENDED,null,reason);active.ended=at;active=null;
    }
    private static int side(double price,double level){return Double.compare(price,level);}
    private static String sideName(int side){return side>0?"TOP_SIDE":side<0?"BOTTOM_SIDE":"UNKNOWN";}
    private void transition(Active r,long at,String kind,String next,CandleUpdate bar,String detail){String before=r.state;r.state=next;emit(r,at,kind,before,next,bar,detail);}
    private void emit(Active r,long at,String kind,String before,String after,CandleUpdate bar,String detail){
        if(r!=null)r.lastEvent=kind;
        events.add(new Event(symbol+":dow50:"+events.size(),at,r==null?null:r.wave.id(),kind,before,after,
            bar==null?null:bar.candle().bucketStartTimeMsc(),bar==null?null:bar.candle().close(),detail));
    }
    private void observe(CandleUpdate bar){
        var r=active;var c=bar.candle();String reason="MONITORED";
        if(r==null||r.wave.recognizedAtMsc()>c.bucketStartTimeMsc())reason="NO_REFERENCE_KNOWN_AT_BAR_OPEN";
        else {
            long at=bar.availableAtMsc();double mid=r.wave.level50();int from=previousClose==null?0:side(previousClose,mid);
            if(from==0)from=r.side;
            int closeSide=side(c.close(),mid);
            boolean touch=c.low()<=mid&&c.high()>=mid;
            double distance=c.low()>mid?c.low()-mid:c.high()<mid?mid-c.high():0;
            if(distance<=tolerance&&!r.near)emit(r,at,"APPROACH",r.state,r.state,bar,"Range distance to 50 = "+distance+" points; independent tolerance="+tolerance);
            r.near=distance<=tolerance;
            if(touch&&!r.touched){r.touched=true;emit(r,at,"FIRST_TOUCH",r.state,r.state,bar,"Closed M5 range includes 50; intrabar order not inferred");}
            // Existing hypotheses were known before this bar, unlike a hypothesis created at its close.
            if(r.breakStatus.equals("CONFIRMED_M5_CLOSE")) {
                boolean target=r.breakFrom>0?c.low()<=r.wave.low():c.high()>=r.wave.high();
                if(closeSide==r.breakFrom){r.breakStatus="INVALIDATED";transition(r,at,"BREAK_INVALIDATED_FALSE_BREAK",WATCH,bar,"M5 close returned to original side; experimental break invalidated"+(target?"; destination also observed inside this bar, no execution ordering inferred":""));}
                else if(target){r.breakStatus="TARGET_OBSERVED";transition(r,at,"BREAK_DESTINATION_OBSERVED",WATCH,bar,"Price range reached the opposite extreme; observation, not guaranteed route or order result");}
            }
            if(r.rejectionStatus.equals("CANDIDATE_UNVALIDATED")) {
                boolean target=r.rejectionFrom>0?c.high()>=r.wave.high():c.low()<=r.wave.low();
                if(closeSide==-r.rejectionFrom){r.rejectionStatus="INVALIDATED";transition(r,at,"REJECTION_INVALIDATED",WATCH,bar,"Close beyond 50 opposes the pending return hypothesis");}
                else if(target){r.rejectionStatus="TARGET_OBSERVED_UNVALIDATED";transition(r,at,"RETURN_DESTINATION_OBSERVED",WATCH,bar,"Return extreme observed; this does not validate a definitive rejection rule");}
            }
            if(from!=0) {
                boolean attempted=from>0?c.low()<mid:c.high()>mid;
                if(attempted)emit(r,at,"BREAK_ATTEMPT",r.state,r.state,bar,"Range penetrated 50 from "+sideName(from)+"; no intrabar chronology assumed");
                if(closeSide==-from) {
                    r.breakFrom=from;r.breakStatus="CANDIDATE";
                    transition(r,at,"BREAK_CANDIDATE",BREAK,bar,"Candidate close crossing from "+sideName(from));
                    r.breakStatus="CONFIRMED_M5_CLOSE";
                    emit(r,at,"BREAK_CONFIRMED_EXPERIMENTAL_M5_CLOSE",BREAK,BREAK,bar,"Experimental confirmation is this M5 close beyond 50. Possible destination="+(from>0?r.wave.low():r.wave.high())+"; no trade signal");
                } else if(touch&&closeSide==from&&!r.rejectionStatus.equals("CANDIDATE_UNVALIDATED")) {
                    r.rejectionFrom=from;r.rejectionStatus="CANDIDATE_UNVALIDATED";
                    transition(r,at,"REJECTION_CANDIDATE",REJECT,bar,"Explicit RANGE_TOUCH_CLOSE_SAME_SIDE hypothesis only; no validated rejection criterion. Possible return="+(from>0?r.wave.high():r.wave.low()));
                }
            } else if(touch)emit(r,at,"APPROACH_SIDE_AMBIGUOUS",r.state,r.state,bar,"No prior non-equal close establishes a side; no directional hypothesis");
            if(c.high()>=r.wave.high()||c.low()<=r.wave.low())emit(r,at,"WAVE_EXTREME_OBSERVED",r.state,r.state,bar,
                "Top reached="+(c.high()>=r.wave.high())+"; bottom reached="+(c.low()<=r.wave.low())+"; both in one bar do not establish an intrabar path");
            if(closeSide!=0)r.side=closeSide;
        }
        observations.add(new Observation(bar.availableAtMsc(),c.bucketStartTimeMsc(),r==null?null:r.wave.id(),r==null?WAIT:r.state,c.close(),
            r==null?null:Math.abs(c.close()-r.wave.level50()),r==null?null:Math.abs(c.close()-r.wave.high()),r==null?null:Math.abs(c.close()-r.wave.low()),
            r==null?"UNKNOWN":sideName(r.side),r==null?"NONE":r.breakStatus,r==null?"NONE":r.rejectionStatus,reason));
    }
    public Snapshot snapshot(){return new Snapshot(symbol,5,available,"CONFIRMED_MOVEMENT_V2",tolerance,
        "EXPERIMENTAL_M5_CLOSE","UNVALIDATED_RANGE_TOUCH_CLOSE_SAME_SIDE",active==null?WAIT:active.state,
        active==null?null:active.wave.id(),references.stream().map(Active::snapshot).toList(),List.copyOf(events),List.copyOf(observations),formingWave);}
    public static Snapshot analyze(String symbol,List<CandleUpdate> candles,DowStructureEngine.Snapshot dow,long cutoff){
        var engine=new DowFiftyEngine(symbol);
        // The principal snapshot already scopes its own session. Never monitor unrelated warmup days.
        long day=dow.pivots().isEmpty()?cutoff/86400000L:dow.pivots().getLast().extreme().extremeTimeMsc()/86400000L;
        engine.advance(dow,candles.stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==day).toList(),cutoff);return engine.snapshot();
    }
}
