package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient;
import java.util.*;
import java.io.IOException;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalChopStudy;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.DmiStudy;
import java.time.*;

/** Reads the existing shared tick; REST refresh only at initialization, reconnect or M5 rollover. */
@Service
public final class ThreeCandleLive {
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private final GetMt5CandlesUseCase candles;
    private final Mt5TickWebSocketHandler ticks;
    private final Mt5Symbol symbol;
    private final Mt5ProcessClient warmupClient;
    private final Clock clock;
    private List<CandleUpdate> base = List.of();
    private long bucket = -1, lastTick = -1;
    private long patternMinute=-1;
    private String patternDate="";
    private List<br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle> patternMinutes=List.of();
    private boolean reconnect = true;
    private final Map<Integer, LiveFeed> feeds = new HashMap<>();
    public static final class LiveFeed implements ThreeCandleMarketFeed {
        private CandlePipeline pipeline;
        private H1SessionPreparation preparation;
        private LiveFeed(String symbol,int minutes) { pipeline=new CandlePipeline(symbol,minutes); }
        public CandlePipeline pipeline() { return pipeline; }
    }
    public record View(String symbol, int timeframeMinutes, String status, Long lastTickMsc, List<CandleUpdate> candles, ThreeCandleStructure.Snapshot structure,long availableAtMsc,DowStructureEngine.Snapshot dow, MicroDowEngine.Snapshot microDow, DowFiftyEngine.Snapshot dowFifty, OpeningStructureContext.Snapshot openingStructure, MicroDowFiftyEngine.Snapshot microDowFifty, ThreeCandleVisualAudit.Snapshot patternAudit, ExperimentalThreeCandlePatternDetector.Snapshot patternStudy, ExperimentalChopStudy.Snapshot chop, DmiStudy.Snapshot dmi) {}
    public ThreeCandleLive(GetMt5CandlesUseCase candles, Mt5TickWebSocketHandler ticks, Mt5Symbol symbol) {
        this(candles,ticks,symbol,null);
    }
    @Autowired
    public ThreeCandleLive(GetMt5CandlesUseCase candles, Mt5TickWebSocketHandler ticks, Mt5Symbol symbol, Mt5ProcessClient warmupClient) {
        this(candles,ticks,symbol,warmupClient,Clock.system(ZoneId.of("America/Sao_Paulo")));
    }
    public ThreeCandleLive(GetMt5CandlesUseCase candles, Mt5TickWebSocketHandler ticks, Mt5Symbol symbol, Mt5ProcessClient warmupClient, Clock clock) {
        this.candles=candles; this.ticks=ticks; this.symbol=symbol; this.warmupClient=warmupClient; this.clock=clock;
    }
    public View snapshot(String requested, int minutes) throws IOException, InterruptedException {
        return snapshot(requested, minutes, 6);
    }
    public View snapshot(String requested, int minutes, int maxLevels) throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try { return readSnapshot(requested, minutes, maxLevels); }
        finally { lock.unlock(); }
    }
    private View readSnapshot(String requested, int minutes, int maxLevels) throws IOException, InterruptedException {
        if (!symbol.value().equals(requested)) throw new IllegalArgumentException("Only the configured live contract is available: "+symbol.value());
        if (minutes!=5 && minutes!=15) throw new IllegalArgumentException("Supported timeframes: M5, M15");
        long now=LocalDateTime.now(clock).toInstant(ZoneOffset.UTC).toEpochMilli();
        var shared = ticks.latestAvailableCandle();
        var tick = shared.map(Mt5TickWebSocketHandler.SharedCandle::tick);
        long currentBucket = tick.map(t -> t.timeMsc()/300000*300000).orElse(bucket);
        if (base.isEmpty() || reconnect && tick.isPresent() || currentBucket > bucket) {
            var raw = candles.execute(); var values = new ArrayList<CandleUpdate>();
            for (int i=0;i<raw.size();i++) {
                var c=raw.get(i).candle(); boolean closed=i<raw.size()-1 || c.time()*1000L+300000L<=now;
                long time=c.time()*1000;
                values.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol.value(),time,c.open(),c.high(),c.low(),c.close()),
                        5,closed,closed ? time+300000 : time));
            }
            // Same bounded candle reader (1,000 M5 bars), only on bootstrap/reconnect.
            // The shared live path remains authoritative for the overlapping recent bars.
            if ((base.isEmpty() || reconnect) && warmupClient != null && !values.isEmpty()) {
                long first = values.getFirst().candle().bucketStartTimeMsc();
                var prefix = new ArrayList<CandleUpdate>();
                for (var c : warmupClient.readCandles()) {
                    long time = c.time()*1000L;
                    if (time >= first) continue;
                    prefix.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol.value(),time,
                            c.open(),c.high(),c.low(),c.close()),5,true,time+300000));
                }
                prefix.addAll(values); values=prefix;
            }
            base=List.copyOf(values); bucket=base.isEmpty() ? -1 : base.getLast().candle().bucketStartTimeMsc();
            // REST is authoritative after a gap; do not pretend the old forming OHLC was complete.
            if (reconnect) feeds.forEach((timeframe, feed) -> {
                var remembered = feed.pipeline.activeLevels();
                feed.pipeline = new CandlePipeline(symbol.value(), timeframe);
                feed.pipeline.rememberLevels(remembered);
            });
            reconnect=tick.isEmpty();
        }
        if (tick.isEmpty()) reconnect=true;
        if (tick.isPresent() && !base.isEmpty()) {
            shared = ticks.latestAvailableCandle();
            tick = shared.map(Mt5TickWebSocketHandler.SharedCandle::tick);
            if (tick.isEmpty()) reconnect = true;
            var t=tick.orElse(null); var last=base.getLast(); long time=last.candle().bucketStartTimeMsc();
            if (!last.closed() && t != null && Double.isFinite(t.last()) && t.last()>0 && t.timeMsc()>=lastTick
                    && t.timeMsc()>=time && t.timeMsc()<time+300000) {
                var c=last.candle();
                var captured=shared.get().candle();
                if (captured!=null && captured.bucketStartTimeMsc()==time) c=captured;
                var copy=new ArrayList<>(base);
                copy.set(copy.size()-1,new CandleUpdate(new IntrabarCandleSnapshot(symbol.value(),time,c.open(),
                        Math.max(c.high(),t.last()),Math.min(c.low(),t.last()),t.last()),5,false,t.timeMsc()));
                base=List.copyOf(copy); lastTick=t.timeMsc();
            }
        }
        var feed=feeds.computeIfAbsent(minutes,m -> new LiveFeed(symbol.value(),m));
        var visible=feed.pipeline.snapshot();
        long lastVisible=visible.isEmpty() ? -1 : visible.getLast().candle().bucketStartTimeMsc();
        for (var update : CandleAggregation.aggregate(base,minutes)) {
            if (update.candle().bucketStartTimeMsc()<lastVisible) continue;
            feed.pipeline.accept(update);
        }
        var received=feed.pipeline.snapshot();
        if(minutes==5 && !received.isEmpty()) {
            var feedDate=LocalDate.ofEpochDay(received.getLast().candle().bucketStartTimeMsc()/86400000L);
            var today=LocalDate.now(clock);
            var date=feedDate.isAfter(today)?feedDate:today;
            if(feed.preparation==null || !feed.preparation.sessionDate().equals(date.toString()))
                feed.preparation=H1SessionPreparation.prepare(symbol.value(),date,date.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli(),received);
            feed.pipeline.prepare(feed.preparation);
        }
        long available=Math.max(now,received.isEmpty()?0:received.getLast().availableAtMsc());
        var structure=feed.pipeline.structure(maxLevels);
        var dow=minutes==5?DowStructureEngine.analyze(symbol.value(),received,structure,available):null;
        var micro=minutes==5 ? MicroDowEngine.analyze(symbol.value(),received,structure,available) : null;
        ExperimentalThreeCandlePatternDetector.Snapshot patternStudy=null;
        if(minutes==5) {
            String date=structure.preparation()!=null?structure.preparation().sessionDate():LocalDate.ofEpochDay(available/86400000L).toString();
            if(!patternDate.equals(date)){patternDate=date;patternMinutes=List.of();patternMinute=-1;}
            if(warmupClient!=null && patternMinute!=available/60000L) {
                patternMinute=available/60000L;
                try {var read=warmupClient.readCandles(symbol.value(),"M1",LocalDate.parse(date),LocalDate.parse(date));patternMinutes=read==null?List.of():List.copyOf(read);}
                catch(IOException unavailableM1) { /* Keep known native minutes; no fabricated sequence or trigger. */ }
            }
            patternStudy=ThreeCandlePatternStudy.analyze(symbol.value(),date,received,patternMinutes,structure.preparation(),available);
        }
        return new View(symbol.value(),minutes,tick.isPresent() ? "LIVE" : "UNAVAILABLE",lastTick<0?null:lastTick,received,
                structure,available,dow,
                micro,
                dow==null?null:DowFiftyEngine.analyze(symbol.value(),received,dow,available),
                minutes==5 ? OpeningStructureContext.analyze(symbol.value(),received,structure,available) : null,
                micro==null?null:MicroDowFiftyEngine.analyze(micro,available),
                patternStudy==null?null:patternStudy.visual(),patternStudy,
                minutes==5 ? ExperimentalChopStudy.analyze(symbol.value(),received,available) : null,
                minutes==5 ? DmiStudy.analyze(symbol.value(),received,available) : null);
    }
}
