package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** M1 close observations, never an invented tick path. Official M5 is validation only. */
public final class ProgressiveM1History {
    private ProgressiveM1History() {}
    public static List<CandleUpdate> build(String symbol, List<CandleUpdate> official,
                                           List<Mt5Candle> minutes, long sessionOpen) {
        var source = new TreeMap<Long,Mt5Candle>();
        long previous = -1;
        for (var m : minutes) {
            long t = Math.multiplyExact(m.time(),1000L);
            if (t % 60000 != 0 || t <= previous) throw new IllegalArgumentException("M1 fora de ordem/alinhamento");
            // Reuse OHLC validation with an M5-aligned timestamp; retain the real M1 key below.
            new IntrabarCandleSnapshot(symbol,t/300000*300000,m.open(),m.high(),m.low(),m.close());
            source.put(t,m); previous=t;
        }
        var result = new ArrayList<CandleUpdate>();
        for (var bar : official) {
            if (!bar.closed() || bar.timeframeMinutes()!=5 || !bar.candle().symbol().equals(symbol))
                throw new IllegalArgumentException("M5 oficial fechado requerido");
            var c=bar.candle(); long t=c.bucketStartTimeMsc();
            if (bar.availableAtMsc()<=sessionOpen) { result.add(bar); continue; }
            double open=0, high=Double.NEGATIVE_INFINITY, low=Double.POSITIVE_INFINITY;
            for (int i=0;i<5;i++) {
                var m=source.get(t+i*60000L);
                if (m==null) {
                    if(i==0 || i==4) throw new IllegalArgumentException("M1 inicial/final ausente em "+symbol+" @ "+(t+i*60000L));
                    // Interior gaps remain gaps, disclosed by Replay.missingM1Times().
                    // Never claim absence of trades or manufacture an M1 bar.
                    continue;
                }
                if(i==0)open=m.open(); high=Math.max(high,m.high()); low=Math.min(low,m.low());
                var partial=new IntrabarCandleSnapshot(symbol,t,open,high,low,m.close());
                if(i==4 && !partial.equals(c)) throw new IllegalArgumentException("M1 agregado diverge do M5 oficial em "+symbol+" @ "+t);
                // Knowledge of the completed minute is available only at its close.
                result.add(new CandleUpdate(partial,5,i==4,t+(i+1)*60000L));
            }
        }
        return List.copyOf(result);
    }
}
