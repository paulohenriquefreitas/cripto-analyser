package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api;

import java.util.*;

/** Archives the browser's display-only decisions. Never selects levels or feeds an engine. */
public final class M5VisualAuditStore {
    public record Pivot(String id,String type,double price,long extremeTimeMsc,long confirmedAtMsc,
                        String state,Long brokenAtMsc,int timeframeMinutes) {}
    public record Replacement(Pivot oldPivot,Pivot newPivot,double distancePoints,long atMsc,String reason) {}
    public record Submission(String scope,String symbol,double tolerance,long availableAtMsc,List<Replacement> replacements) {}
    private final Map<String,LinkedHashMap<String,Replacement>> history=new LinkedHashMap<>();
    private String key(Submission s){return s.scope()+":"+s.symbol()+":"+s.tolerance();}
    private void validate(Pivot p,Submission s) {
        if(p==null || p.timeframeMinutes()!=5 || !Set.of("TOP","BOTTOM").contains(p.type())
            || !p.id().equals(s.symbol()+":5:"+p.extremeTimeMsc()+":"+p.type())
            || !Double.isFinite(p.price()) || p.price()<=0 || p.confirmedAtMsc()<=p.extremeTimeMsc()
            || p.confirmedAtMsc()>s.availableAtMsc())throw new IllegalArgumentException("Invalid original M5 audit pivot");
    }
    public synchronized void record(Submission s) {
        if(s.scope()==null || s.scope().length()>120 || s.symbol()==null || !s.symbol().matches("[A-Z0-9_$]{1,24}")
            || !Set.of(15.0,30.0,50.0).contains(s.tolerance()) || s.replacements()==null || s.replacements().size()>10000)
            throw new IllegalArgumentException("Invalid M5 visual audit");
        for(var r:s.replacements()) {
            validate(r.oldPivot(),s);validate(r.newPivot(),s);
            var a=r.oldPivot();var b=r.newPivot();
            int order=Comparator.comparingLong(Pivot::confirmedAtMsc).thenComparingLong(Pivot::extremeTimeMsc).thenComparing(Pivot::id).compare(a,b);
            if(order>=0 || !"SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE".equals(r.reason())
                || r.atMsc()!=b.confirmedAtMsc() || r.distancePoints()!=Math.abs(a.price()-b.price()) || r.distancePoints()>s.tolerance())
                throw new IllegalArgumentException("Invalid causal M5 visual replacement");
        }
        var records=history.computeIfAbsent(key(s),ignored->new LinkedHashMap<>());
        for(var r:s.replacements())records.put(r.oldPivot().id()+"/"+r.newPivot().id(),r);
    }
    private Pivot at(Pivot p,long cutoff) {
        return p.brokenAtMsc()!=null && p.brokenAtMsc()>cutoff
            ? new Pivot(p.id(),p.type(),p.price(),p.extremeTimeMsc(),p.confirmedAtMsc(),"CONFIRMED",null,p.timeframeMinutes()) : p;
    }
    public synchronized List<Replacement> read(String scope,String symbol,double tolerance,long availableAtMsc) {
        return history.getOrDefault(scope+":"+symbol+":"+tolerance,new LinkedHashMap<>()).values().stream()
            .filter(r->r.atMsc()<=availableAtMsc)
            .map(r->new Replacement(at(r.oldPivot(),availableAtMsc),at(r.newPivot(),availableAtMsc),r.distancePoints(),r.atMsc(),r.reason())).sorted(Comparator.comparingLong(Replacement::atMsc)
                .thenComparing(r->r.newPivot().id()).thenComparing(r->r.oldPivot().id())).toList();
    }
}
