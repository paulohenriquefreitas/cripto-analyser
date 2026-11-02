package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import org.ta4j.core.*;
import org.ta4j.core.indicators.ChopIndicator;
import java.time.*;
import java.util.*;

/** Read-only regime experiment. No connection to pattern recognition, strategies or orders. */
public final class ExperimentalChopStudy {
    public record Reading(int period, Double value, Double delta, String slope, String regime, String status) {}
    public record Point(long candleTimeMsc,long availableAtMsc,boolean provisional,double open,double high,double low,double close,
                        Double displacement,String priceDirection,double observedSessionHigh,double observedSessionLow,List<Reading> readings) {}
    public record Snapshot(String symbol,long availableAtMsc,String policy,String deltaBasis,List<Point> confirmed,Point provisional) {}
    private final String symbol;
    private final boolean sessionOnly;
    private final NavigableMap<Long,CandleUpdate> closed=new TreeMap<>();
    private final List<Point> history=new ArrayList<>();
    private Point provisional;
    private long available=-1;
    public ExperimentalChopStudy(String symbol,boolean sessionOnly){this.symbol=symbol;this.sessionOnly=sessionOnly;}

    public void advance(CandleUpdate update) {
        if(update.timeframeMinutes()!=5||!update.candle().symbol().equals(symbol))throw new IllegalArgumentException("CHOP requires one M5 series");
        if(update.availableAtMsc()<available)throw new IllegalArgumentException("Rebuild CHOP for regressing time");
        long t=update.candle().bucketStartTimeMsc();
        var old=closed.get(t);
        if(old!=null){if(!old.equals(update))throw new IllegalArgumentException("Cannot rewrite confirmed CHOP candle");return;}
        if(!closed.isEmpty()&&t<=closed.lastKey())throw new IllegalArgumentException("CHOP requires chronological candles");
        var bars=new ArrayList<>(closed.values());bars.add(update);
        if(sessionOnly)bars.removeIf(b->b.candle().bucketStartTimeMsc()/86400000L!=t/86400000L);
        // ATR(1) needs one preceding close; 22 bars suffice for the longest 21-TR window.
        if(bars.size()>22)bars=new ArrayList<>(bars.subList(bars.size()-22,bars.size()));
        BarSeries series=new BaseBarSeriesBuilder().withName("experimental-chop").build();
        var nums=series.numFactory();
        for(var b:bars){var c=b.candle();var begin=Instant.ofEpochMilli(c.bucketStartTimeMsc());
            series.addBar(new BaseBar(Duration.ofMinutes(5),begin,begin.plusSeconds(300),nums.numOf(c.open()),nums.numOf(c.high()),nums.numOf(c.low()),nums.numOf(c.close()),nums.zero(),nums.zero(),0L));}
        int index=series.getEndIndex();
        var readings=new ArrayList<Reading>();
        Point prior=history.isEmpty()?null:history.getLast();
        if(sessionOnly&&prior!=null&&prior.candleTimeMsc()/86400000L!=t/86400000L)prior=null;
        for(int n:new int[]{7,14,21}) {
            String status="VALID";Double value=null,delta=null;
            if(bars.size()<n+1)status="INSUFFICIENT_HISTORY"; // n TR values, each with a known previous close.
            else {
                double hi=Double.NEGATIVE_INFINITY,lo=Double.POSITIVE_INFINITY;
                for(int i=bars.size()-n;i<bars.size();i++){hi=Math.max(hi,bars.get(i).candle().high());lo=Math.min(lo,bars.get(i).candle().low());}
                if(hi==lo)status="ZERO_RANGE";
                else {try {double raw=new ChopIndicator(series,n,100).getValue(index).doubleValue();
                    if(Double.isFinite(raw))value=raw;else status="NON_FINITE";
                } catch(ArithmeticException|NumberFormatException invalid){status="NON_FINITE";}}
            }
            if(value!=null&&prior!=null){var previous=prior.readings().stream().filter(r->r.period()==n).findFirst().orElseThrow();if(previous.value()!=null)delta=value-previous.value();}
            readings.add(new Reading(n,value,delta,delta==null?"UNAVAILABLE":delta>0?"RISING":delta<0?"FALLING":"FLAT",value==null?"UNAVAILABLE":value<38.2?"DIRECTIONAL":value>61.8?"CHOPPY":"INTERMEDIATE",status));
        }
        var c=update.candle();double high=c.high(),low=c.low();
        for(var b:closed.values())if(b.candle().bucketStartTimeMsc()/86400000L==t/86400000L){high=Math.max(high,b.candle().high());low=Math.min(low,b.candle().low());}
        Double displacement=closed.isEmpty()?null:c.close()-closed.lastEntry().getValue().candle().close();
        var point=new Point(t,update.availableAtMsc(),!update.closed(),c.open(),c.high(),c.low(),c.close(),displacement,displacement==null?"UNAVAILABLE":displacement>0?"UP":displacement<0?"DOWN":"FLAT",high,low,List.copyOf(readings));
        if(update.closed()){closed.put(t,update);history.add(point);provisional=null;}else provisional=point;
        available=update.availableAtMsc();
    }
    public Snapshot snapshot(long cutoff){
        if(cutoff<available)throw new IllegalArgumentException("Rebuild CHOP for regressing cursor");
        long day=cutoff/86400000L;
        return new Snapshot(symbol,cutoff,sessionOnly?"SESSION_ONLY_N_PLUS_1":"CONTINUOUS_AVAILABLE_M5_INCLUDES_OVERNIGHT_TR", "PREVIOUS_CONFIRMED_M5",
            history.stream().filter(p->p.candleTimeMsc()/86400000L==day).toList(),provisional!=null&&provisional.candleTimeMsc()/86400000L==day?provisional:null);
    }
    public static Snapshot analyze(String symbol,List<CandleUpdate> candles,long cutoff){
        var study=new ExperimentalChopStudy(symbol,false);
        var known=candles.stream().filter(b->b.timeframeMinutes()==5&&b.candle().symbol().equals(symbol)&&b.availableAtMsc()<=cutoff)
            .sorted(Comparator.comparingLong(CandleUpdate::availableAtMsc)).toList();
        var warmup=known.stream().filter(b->b.candle().bucketStartTimeMsc()/86400000L<cutoff/86400000L).toList();
        warmup.subList(Math.max(0,warmup.size()-22),warmup.size()).forEach(study::advance);
        known.stream().filter(b->b.candle().bucketStartTimeMsc()/86400000L==cutoff/86400000L).forEach(study::advance);
        return study.snapshot(cutoff);
    }
}
