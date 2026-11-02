package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Informational 2+1 candidates only. Never publishes structural pivots or drives entry decisions. */
public final class DowAnticipationStudy {
    public record Candidate(String id,String type,double price,CandleUpdate leftA,CandleUpdate leftB,
                            CandleUpdate origin,CandleUpdate rightC,long availableAtMsc,String status,
                            CandleUpdate rightD,Long resolvedAtMsc,String reason) {}
    private static final long M5=300000,DAY=86400000;
    private final List<CandleUpdate> bars=new ArrayList<>();
    private final LinkedHashMap<String,Candidate> candidates=new LinkedHashMap<>();
    private long session=Long.MIN_VALUE;
    public void advance(CandleUpdate bar) {
        if(!bar.closed()||bar.timeframeMinutes()!=5)return;
        long t=bar.candle().bucketStartTimeMsc(),day=t/DAY;
        if(day!=session){bars.clear();candidates.clear();session=day;}
        if(!bars.isEmpty()&&t<=bars.getLast().candle().bucketStartTimeMsc())return;
        for(var entry:candidates.entrySet()) {
            var c=entry.getValue();if(!c.status().equals("CANDIDATE_2_1"))continue;
            long expected=c.rightC().candle().bucketStartTimeMsc()+M5;
            if(t<expected)continue;
            boolean contiguous=t==expected;
            boolean retained=c.type().equals("TOP")?c.price()>bar.candle().high():c.price()<bar.candle().low();
            String status=!contiguous?"INCONCLUSIVE_GAP":retained?"CONFIRMED_2_2":"INVALIDATED";
            entry.setValue(new Candidate(c.id(),c.type(),c.price(),c.leftA(),c.leftB(),c.origin(),c.rightC(),c.availableAtMsc(),
                status,contiguous?bar:null,bar.availableAtMsc(),!contiguous?"D ausente; nenhuma confirmacao inferida":retained?
                "D fechado preservou o extremo; decisao estrutural continua independente":"D igualou ou ultrapassou o extremo candidato"));
        }
        bars.add(bar);
        if(bars.size()>4)bars.removeFirst();
        if(bars.size()!=4)return;
        for(int i=1;i<4;i++)if(bars.get(i).candle().bucketStartTimeMsc()!=bars.get(i-1).candle().bucketStartTimeMsc()+M5)return;
        var a=bars.get(0);var b=bars.get(1);var origin=bars.get(2);var c=bars.get(3);
        for(String type:List.of("TOP","BOTTOM")) {
            boolean top=type.equals("TOP");double price=top?origin.candle().high():origin.candle().low();
            boolean qualifies=List.of(a,b,c).stream().allMatch(x->top?price>x.candle().high():price<x.candle().low());
            if(qualifies){String id=origin.candle().symbol()+":2+1:"+origin.candle().bucketStartTimeMsc()+":"+type;
                candidates.put(id,new Candidate(id,type,price,a,b,origin,c,bar.availableAtMsc(),"CANDIDATE_2_1",null,null,
                    "Extremo maior/menor que A, B e C; conhecido ao fechar C; nao e entrada nem pivo Dow confirmado"));}
        }
    }
    public List<Candidate> snapshot(){return List.copyOf(candidates.values());}
}
