package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** UI-independent deterministic replay; progressive observations enter the existing partial-bar pipeline. */
public final class ThreeCandleReplay implements ThreeCandleMarketFeed {
    public enum Mode { CLOSED_CANDLE, PROGRESSIVE_M1 }
    private final Mode mode;
    public Mode mode() { return mode; }
    public List<Long> missingM1Times() {
        if(mode!=Mode.PROGRESSIVE_M1) return List.of();
        var missing=new ArrayList<Long>();
        var known=new HashSet<Long>();
        history.stream().filter(c->c.availableAtMsc()>start).forEach(c->known.add(c.availableAtMsc()));
        for(var c:history) if(c.closed() && c.availableAtMsc()>start)
            for(int i=1;i<=5;i++) {
                long availability=c.candle().bucketStartTimeMsc()+i*60000L;
                if(availability>start && !known.contains(availability)) missing.add(availability-60000);
            }
        return List.copyOf(missing);
    }
    public enum Status { PAUSED, PLAYING, COMPLETED }
    private final List<CandleUpdate> history;
    private final String symbol;
    private final int timeframe;
    private final long start;
    private final H1SessionPreparation preparation;
    private CandlePipeline pipeline;
    private int cursor, speed = 1;
    private Status status = Status.PAUSED;
    private long lastNanos;
    private double pending;
    public ThreeCandleReplay(String symbol, int timeframe, List<CandleUpdate> history, long start) {
        this(symbol,timeframe,history,start,null);
    }
    public ThreeCandleReplay(String symbol, int timeframe, List<CandleUpdate> history, long start, H1SessionPreparation preparation) {
        this(symbol,timeframe,history,start,preparation,Mode.CLOSED_CANDLE);
    }
    public ThreeCandleReplay(String symbol, int timeframe, List<CandleUpdate> history, long start,
                             H1SessionPreparation preparation, Mode mode) {
        this.mode = Objects.requireNonNull(mode);
        if(mode==Mode.PROGRESSIVE_M1 && timeframe!=5) throw new IllegalArgumentException("Replay progressivo requer M5");
        this.symbol = symbol; this.timeframe = timeframe; this.start = start; this.preparation=preparation;
        long previous = -1, previousAvailability = -1;
        CandleUpdate last = null;
        for (var candle : history) {
            if (!candle.candle().symbol().equals(symbol) || candle.timeframeMinutes() != timeframe
                    || (mode==Mode.CLOSED_CANDLE && (!candle.closed() || candle.candle().bucketStartTimeMsc()<=previous))
                    || candle.candle().bucketStartTimeMsc()<previous || candle.availableAtMsc()<=previousAvailability
                    || (last!=null && (candle.candle().bucketStartTimeMsc()==previous ? last.closed() : !last.closed())))
                throw new IllegalArgumentException("Replay requires ordered closed candles of one series");
            previous = candle.candle().bucketStartTimeMsc(); previousAvailability=candle.availableAtMsc(); last=candle;
        }
        if(last!=null && !last.closed()) throw new IllegalArgumentException("Replay history must end with a closed candle");
        this.history = List.copyOf(history); reset();
    }
    public CandlePipeline pipeline() { return pipeline; }
    public Status status() { return status; }
    public int speed() { return speed; }
    public List<CandleUpdate> candles() {
        if(preparation==null) return pipeline.snapshot();
        long day=java.time.LocalDate.parse(preparation.sessionDate()).toEpochDay();
        var known = pipeline.snapshot();
        var previousDays = known.stream().map(c -> c.candle().bucketStartTimeMsc()/86400000L)
                .filter(d -> d < day).distinct().sorted(Comparator.reverseOrder()).limit(3).toList();
        return known.stream().filter(c -> {
            long candleDay = c.candle().bucketStartTimeMsc()/86400000L;
            return candleDay == day || previousDays.contains(candleDay);
        }).toList();
    }
    public long availableAtMsc() {
        return Math.max(start,cursor==0 ? start : history.get(cursor-1).availableAtMsc());
    }
    public void reset() {
        pipeline = new CandlePipeline(symbol,timeframe); cursor = 0; pending = 0; status = Status.PAUSED;
        while (cursor < history.size() && history.get(cursor).availableAtMsc() <= start) pipeline.accept(history.get(cursor++));
        if(preparation!=null) pipeline.prepare(preparation);
        if (cursor == history.size()) status = Status.COMPLETED;
    }
    public void play(long nanos) { if (status != Status.COMPLETED) { status = Status.PLAYING; lastNanos = nanos; } }
    public void pause(long nanos) { advance(nanos); if (status != Status.COMPLETED) status = Status.PAUSED; }
    public void speed(int value, long nanos) {
        if (value != 1 && value != 2 && value != 5 && value != 10) throw new IllegalArgumentException("Invalid speed");
        advance(nanos); speed = value;
    }
    /** One observation per second at 1x: M5 close or M1 close, depending on mode. */
    public void advance(long nanos) {
        if (status != Status.PLAYING) return;
        pending += Math.max(0, nanos-lastNanos) / 1_000_000_000.0 * speed; lastNanos = nanos;
        while (pending >= 1 && cursor < history.size()) { consume(); pending--; }
    }
    public void next() { status = Status.PAUSED; pending = 0; consume(); }
    private void consume() {
        if (cursor < history.size()) pipeline.accept(history.get(cursor++));
        if (cursor == history.size()) status = Status.COMPLETED;
    }
}
