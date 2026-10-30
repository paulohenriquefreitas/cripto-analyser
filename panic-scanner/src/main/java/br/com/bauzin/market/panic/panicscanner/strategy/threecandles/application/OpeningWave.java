package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Causal session-boundary reference, deliberately outside detector pivots and classifications. */
public final class OpeningWave {
    public record Anchor(String id, StructuralLevel.Type type, double price, long formationTimeMsc, long observedAtMsc) {}
    public record Event(long atMsc, String kind, String reason) {}
    public record Snapshot(String status, List<Anchor> anchors, String fromId, String toId, Long knownAtMsc,
                           String reason, List<Event> events) {}
    public static Snapshot analyze(String symbol,List<CandleUpdate> bars,List<DowStructureEngine.Pivot> pivots) {
        if (bars.isEmpty() || Math.floorMod(bars.getFirst().candle().bucketStartTimeMsc(),86400000L)!=9*3600000L)
            return new Snapshot("UNAVAILABLE",List.of(),null,null,null,"First closed session bar must be 09:00 in feed civil time",List.of());
        var first=bars.getFirst();var candle=first.candle();
        var high=new Anchor(symbol+":opening:TOP:"+candle.bucketStartTimeMsc(),StructuralLevel.Type.TOP,candle.high(),candle.bucketStartTimeMsc(),first.availableAtMsc());
        var low=new Anchor(symbol+":opening:BOTTOM:"+candle.bucketStartTimeMsc(),StructuralLevel.Type.BOTTOM,candle.low(),candle.bucketStartTimeMsc(),first.availableAtMsc());
        var anchors=List.of(high,low);
        var events=new ArrayList<Event>();events.add(new Event(first.availableAtMsc(),"OPENING_OBSERVED","First closed candle range; neither endpoint is a confirmed pivot"));
        var target=pivots.stream().filter(p->p.participation().equals("STRUCTURE")).findFirst().orElse(null);
        if(target==null) return new Snapshot("WAITING",anchors,null,null,null,"Waiting for first unambiguous confirmed extreme",List.copyOf(events));
        var p=target.extreme();var anchor=p.type()==StructuralLevel.Type.BOTTOM?high:low;
        boolean ambiguous=pivots.stream().anyMatch(v->v.participation().equals("LOCAL") && v.extreme().confirmedAtMsc()<=p.confirmedAtMsc());
        boolean exceeded=bars.stream().filter(b->b.availableAtMsc()<=p.confirmedAtMsc() && b!=first)
            .anyMatch(b->anchor.type()==StructuralLevel.Type.TOP ? b.candle().high()>=anchor.price() : b.candle().low()<=anchor.price());
        boolean priceOrder=anchor.type()==StructuralLevel.Type.TOP ? anchor.price()>p.price() : anchor.price()<p.price();
        String reason=ambiguous?"Ambiguous intrabar order before first endpoint":exceeded?"Opening reference exceeded or equalled before endpoint confirmation":!priceOrder?"No directional opening wave":"Opening boundary supported through opposite confirmation; remains a reference, never a detector pivot";
        String status=ambiguous||exceeded||!priceOrder?"REJECTED":"CONNECTED";
        events.add(new Event(p.confirmedAtMsc(),"OPENING_"+status,reason));
        return new Snapshot(status,anchors,anchor.id(),p.id(),p.confirmedAtMsc(),reason,List.copyOf(events));
    }
}
