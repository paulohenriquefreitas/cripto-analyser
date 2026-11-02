package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.ta4j.indicators.MovingAverages;
import org.junit.jupiter.api.Test;
import org.ta4j.core.*;
import org.ta4j.core.indicators.averages.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class MovingAveragesParityTest {
    private BaseBar bar(BarSeries s, int i, double close) {
        var begin=Instant.parse("2026-10-28T23:00:00Z").plusSeconds(i*300L);
        var n=s.numFactory();
        return new BaseBar(Duration.ofMinutes(5),begin,begin.plusSeconds(300),n.numOf(close),n.numOf(close+1),n.numOf(close-1),n.numOf(close),n.numOf(10),n.zero(),0L);
    }
    @Test void incrementalReplacementAndPartialUpdatesMatchDirectTa4jAndRebuild() {
        for(int period:new int[]{9,21}) {
            BarSeries s=new BaseBarSeriesBuilder().build();
            var close=new ClosePriceIndicator(s);
            var sma=MovingAverages.sma(close,period); var ema=MovingAverages.ema(close,period);
            var oldSma=new SMAIndicator(close,period); var oldEma=new EMAIndicator(close,period);
            for(int i=0;i<40;i++) {
                s.addBar(bar(s,i,100+i*.37));
                check(s,period,sma,ema,oldSma,oldEma);
                s.getLastBar().addPrice(s.numFactory().numOf(101+i*.37));
                check(s,period,sma,ema,oldSma,oldEma);
                s.addBar(bar(s,i,102+i*.37),true);
                check(s,period,sma,ema,oldSma,oldEma);
            }
        }
    }
    private void check(BarSeries s,int p,SMAIndicator sma,EMAIndicator ema,SMAIndicator oldSma,EMAIndicator oldEma) {
        int end=s.getEndIndex();
        assertEquals(oldSma.getValue(end),sma.getValue(end));
        assertEquals(oldEma.getValue(end),ema.getValue(end));
        BarSeries rebuilt=new BaseBarSeriesBuilder().build();
        for(int i=0;i<=end;i++) rebuilt.addBar(bar(rebuilt,i,s.getBar(i).getClosePrice().doubleValue()));
        var close=new ClosePriceIndicator(rebuilt);
        assertEquals(new SMAIndicator(close,p).getValue(end),sma.getValue(end));
        assertEquals(new EMAIndicator(close,p).getValue(end),ema.getValue(end));
        assertEquals(s.getLastBar().getEndTime(),rebuilt.getLastBar().getEndTime());
    }
}
