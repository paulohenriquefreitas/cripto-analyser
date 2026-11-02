package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Genuine contiguous M1 prefixes followed by the independent official M5 close. No invented path. */
public final class ProgressiveM1History {
    private ProgressiveM1History() {}
    public record Result(List<CandleUpdate> updates,List<Long> missingM1Times) {}
    public static List<CandleUpdate> build(String symbol,List<CandleUpdate> official,List<Mt5Candle> minutes,long sessionOpen) {
        return buildDetailed(symbol,official,minutes,sessionOpen).updates();
    }
    public static Result buildDetailed(String symbol,List<CandleUpdate> official,List<Mt5Candle> minutes,long sessionOpen) {
        var source=new TreeMap<Long,Mt5Candle>();long previous=-1;
        for(var m:minutes) {
            long t=Math.multiplyExact(m.time(),1000L);
            if(t%60000!=0||t<=previous)throw new IllegalArgumentException("M1 fora de ordem/alinhamento");
            new IntrabarCandleSnapshot(symbol,t/300000*300000,m.open(),m.high(),m.low(),m.close());
            source.put(t,m);previous=t;
        }
        var result=new ArrayList<CandleUpdate>();var missing=new ArrayList<Long>();
        for(var bar:official) {
            if(!bar.closed()||bar.timeframeMinutes()!=5||!bar.candle().symbol().equals(symbol))
                throw new IllegalArgumentException("M5 oficial fechado requerido");
            var c=bar.candle();long t=c.bucketStartTimeMsc();
            if(bar.availableAtMsc()<=sessionOpen){result.add(bar);continue;}
            double open=0,high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;
            boolean contiguous=true,complete=true;
            for(int i=0;i<5;i++) {
                var m=source.get(t+i*60000L);
                if(m==null){missing.add(t+i*60000L);contiguous=false;complete=false;continue;}
                // Data contradictions remain errors, even when another minute is missing.
                if(m.high()>c.high()||m.low()<c.low()||i==0&&m.open()!=c.open()||i==4&&m.close()!=c.close())
                    throw new IllegalArgumentException("M1 agregado diverge do M5 oficial em "+symbol+" @ "+t);
                if(!contiguous)continue; // Missing opening/range cannot be reconstructed from later minutes.
                if(i==0)open=m.open();high=Math.max(high,m.high());low=Math.min(low,m.low());
                var partial=new IntrabarCandleSnapshot(symbol,t,open,high,low,m.close());
                if(i==4&&complete&&!partial.equals(c))throw new IllegalArgumentException("M1 agregado diverge do M5 oficial em "+symbol+" @ "+t);
                if(i<4)result.add(new CandleUpdate(partial,5,false,t+(i+1)*60000L));
            }
            // This is a real M5 observation at its own close, never evidence of a missing M1.
            result.add(bar);
        }
        return new Result(List.copyOf(result),List.copyOf(missing));
    }
}
