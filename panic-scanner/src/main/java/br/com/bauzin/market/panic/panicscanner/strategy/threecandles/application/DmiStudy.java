package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Session-local Wilder DMI(14)/ADX(14); native feed civil clock, no overnight carry. */
public final class DmiStudy {
    private static final int PERIOD = 14;
    public record Point(long candleTimeMsc, long availableAtMsc, boolean provisional,
                        Double plusDi, Double minusDi, Double adx, String regime,
                        Double warmupPlusDi, Double warmupMinusDi) {}
    public record Snapshot(String symbol, long availableAtMsc, int period, List<Point> confirmed, Point provisional) {}
    private static final class State {
        CandleUpdate previous;
        int count, dxCount;
        double tr, plus, minus, adx;
        State copy() { var s = new State(); s.previous=previous; s.count=count; s.dxCount=dxCount;
            s.tr=tr; s.plus=plus; s.minus=minus; s.adx=adx; return s; }
        Point accept(CandleUpdate update) {
            Double p=null, m=null, a=null;
            if (previous != null) {
                var c=update.candle(); var prev=previous.candle();
                double up=c.high()-prev.high(), down=prev.low()-c.low();
                double range=Math.max(c.high()-c.low(), Math.max(Math.abs(c.high()-prev.close()), Math.abs(c.low()-prev.close())));
                double pdm=up>down && up>0 ? up : 0, mdm=down>up && down>0 ? down : 0;
                count++;
                if(count<=PERIOD) { tr+=range; plus+=pdm; minus+=mdm; }
                else { tr=tr-tr/PERIOD+range; plus=plus-plus/PERIOD+pdm; minus=minus-minus/PERIOD+mdm; }
                if(count>=PERIOD) {
                    p=tr==0 ? 0 : 100*plus/tr; m=tr==0 ? 0 : 100*minus/tr;
                    double dx=p+m==0 ? 0 : 100*Math.abs(p-m)/(p+m);
                    dxCount++;
                    if(dxCount<=PERIOD) adx+=dx;
                    if(dxCount==PERIOD) adx/=PERIOD;
                    else if(dxCount>PERIOD) adx=(adx*(PERIOD-1)+dx)/PERIOD;
                    if(dxCount>=PERIOD) a=adx;
                }
            }
            previous=update;
            String regime=classify(p, m, a);
            // Display-only cumulative estimate while Wilder seeds. Never seeds ADX or classification.
            // The first session candle is a neutral anchor: no previous candle means no directional move.
            Double earlyPlus=a==null ? (p!=null ? p : tr==0 ? 0.0 : 100*plus/tr) : null;
            Double earlyMinus=a==null ? (m!=null ? m : tr==0 ? 0.0 : 100*minus/tr) : null;
            return new Point(update.candle().bucketStartTimeMsc(), update.availableAtMsc(), !update.closed(), p, m, a, regime,
                earlyPlus,earlyMinus);
        }
    }
    public static String classify(Double plusDi, Double minusDi, Double adx) {
        if (plusDi==null || minusDi==null || adx==null) return "UNAVAILABLE";
        if (adx<20) return "FLAT";
        if (plusDi.equals(minusDi)) return "INDEFINITE";
        return plusDi>minusDi ? "UP" : "DOWN";
    }
    public static Snapshot analyze(String symbol, List<CandleUpdate> candles, long cutoff) {
        // Filter BEFORE seeding previous OHLC, DM, TR and Wilder smoothing. Chart history stays intact.
        // Use the cursor's native session date, not the computer date or a display timezone shift.
        long sessionStart=Math.floorDiv(cutoff,86400000L)*86400000L+9*3600000L;
        var state=new State(); var confirmed=new ArrayList<Point>(); Point provisional=null;
        var known=candles.stream().filter(b->b.timeframeMinutes()==5 && b.candle().symbol().equals(symbol)
            && b.candle().bucketStartTimeMsc()>=sessionStart
            && b.availableAtMsc()<=cutoff && b.candle().bucketStartTimeMsc()<=cutoff
            && (!b.closed() || b.candle().bucketStartTimeMsc()+300000<=b.availableAtMsc()))
            .sorted(Comparator.comparingLong(CandleUpdate::availableAtMsc).thenComparingLong(b->b.candle().bucketStartTimeMsc())).toList();
        long lastClosed=Long.MIN_VALUE;
        for(var b:known) {
            long time=b.candle().bucketStartTimeMsc();
            if(time<=lastClosed) continue;
            if(b.closed()) { var point=state.accept(b); lastClosed=time; provisional=null;
                if(time/86400000L==cutoff/86400000L) confirmed.add(point);
            } else { provisional=state.copy().accept(b); }
        }
        if(provisional!=null && provisional.candleTimeMsc()/86400000L!=cutoff/86400000L) provisional=null;
        return new Snapshot(symbol, cutoff, PERIOD, List.copyOf(confirmed), provisional);
    }
}
