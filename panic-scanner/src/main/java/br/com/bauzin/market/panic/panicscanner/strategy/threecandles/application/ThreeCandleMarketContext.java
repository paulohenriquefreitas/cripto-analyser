package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Developing leg frozen before C1. Selected confirmed micro pivot first, principal fallback. */
public final class ThreeCandleMarketContext {
    public record Snapshot(String flow,String principalState,String microState,String source,long availableAtMsc,
                           CandleUpdate from,CandleUpdate to, StructuralLevel anchor) {
        public Snapshot(String flow,String principalState,String microState,String source,long at,CandleUpdate from,CandleUpdate to) {
            this(flow,principalState,microState,source,at,from,to,null);
        }
    }
    public static Snapshot unknown(long at){return new Snapshot("UNDEFINED","UNDEFINED","UNDEFINED","INSUFFICIENT_PRIOR_SESSION_EVIDENCE",at,null,null);}
    public static Snapshot capture(String symbol,List<CandleUpdate> bars,DowStructureEngine.Snapshot principal,
                                    MicroDowEngine.Snapshot micro,long before) {
        var known=bars.stream().filter(b->b.closed()&&b.timeframeMinutes()==5&&b.candle().symbol().equals(symbol)
            &&b.availableAtMsc()<=before&&b.candle().bucketStartTimeMsc()<before
            &&b.candle().bucketStartTimeMsc()/86400000L==before/86400000L)
            .sorted(Comparator.comparingLong(b->b.candle().bucketStartTimeMsc())).toList();
        String p=principal.availableAtMsc()<=before?principal.state():"UNDEFINED";
        String m=micro.availableAtMsc()<=before?micro.state():"UNDEFINED";
        if(known.isEmpty())return new Snapshot("UNDEFINED",p,m,"INSUFFICIENT_PRIOR_SESSION_EVIDENCE",before,null,null);
        var last=known.getLast();
        if(last.candle().bucketStartTimeMsc()+300000!=before)
            return new Snapshot("UNDEFINED",p,m,"PRIOR_M5_GAP",before,null,last);
        // Refuse future snapshots entirely: their selected chain can already supersede a known pivot.
        var anchor=micro.availableAtMsc()<=before?latest(micro.pivots().stream()
            .filter(x->micro.selectedPivotIds().contains(x.extreme().id())).toList(),before):null;
        String source="MICRO_DOW_DEVELOPING_LEG";
        if(anchor==null||Double.compare(last.candle().close(),anchor.price())==0) {
            anchor=principal.availableAtMsc()<=before?latest(principal.pivots().stream()
                .filter(x->x.participation().equals("STRUCTURE")).toList(),before):null;
            source="PRINCIPAL_DOW_DEVELOPING_LEG";
        }
        if(anchor==null)return new Snapshot("UNDEFINED",p,m,"NO_CONFIRMED_SESSION_DOW_ANCHOR",before,null,last);
        var selected=anchor;
        var from=known.stream().filter(b->b.candle().bucketStartTimeMsc()==selected.extremeTimeMsc()).findFirst().orElse(null);
        if(from==null)return new Snapshot("UNDEFINED",p,m,"DOW_ANCHOR_CANDLE_UNAVAILABLE",before,null,last);
        int sign=Double.compare(last.candle().close(),anchor.price());
        return new Snapshot(sign>0?"UP":sign<0?"DOWN":"UNDEFINED",p,m,source,before,from,last,anchor);
    }
    private static StructuralLevel latest(List<DowStructureEngine.Pivot> pivots,long before) {
        // Deterministic tie: formation, confirmation, id; no dependence on server list order.
        return pivots.stream().map(DowStructureEngine.Pivot::extreme)
            .filter(x->x.confirmedAtMsc()<=before&&x.extremeTimeMsc()<before
                &&x.extremeTimeMsc()/86400000L==before/86400000L)
            .max(Comparator.comparingLong(StructuralLevel::extremeTimeMsc)
                .thenComparingLong(StructuralLevel::confirmedAtMsc).thenComparing(StructuralLevel::id)).orElse(null);
    }
}
