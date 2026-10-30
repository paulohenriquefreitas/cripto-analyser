package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Independent experimental observer. No trading state and no input into dominant Dow. */
public final class MicroDowEngine {
    public record Snapshot(String symbol, int timeframeMinutes, long availableAtMsc, String state,
                           String criterion, List<DowStructureEngine.Pivot> pivots,
                           List<DowStructureEngine.Movement> movements, List<DowStructureEngine.Event> events,
                           OpeningWave.Snapshot opening, List<String> selectedPivotIds,
                           List<DowStructureEngine.Movement> movementHistory, List<MicroZigZag.Decision> decisions,
                           List<OpeningWave.Snapshot> openingHistory) {}
    private final String symbol;
    private final List<CandleUpdate> bars = new ArrayList<>();
    private final List<DowStructureEngine.Pivot> pivots = new ArrayList<>();
    private final MicroZigZag zigzag = new MicroZigZag();
    private final List<DowStructureEngine.Event> events = new ArrayList<>();
    private final Map<StructuralLevel.Type, StructuralLevel> latest = new EnumMap<>(StructuralLevel.Type.class);
    private final List<OpeningWave.Snapshot> openingHistory=new ArrayList<>();
    private long available = -1;
    public MicroDowEngine(String symbol) { this.symbol = Objects.requireNonNull(symbol); }

    /** Successive known prefixes; confirmations use actual availability of the right closed bar. */
    public void advance(List<CandleUpdate> input, long cutoff) {
        if (cutoff < available) throw new IllegalArgumentException("Rebuild micro Dow for a regressing cursor");
        for (var bar : input.stream().filter(c -> c.closed() && c.timeframeMinutes()==5
                && c.candle().symbol().equals(symbol) && c.availableAtMsc()<=cutoff)
                .sorted(Comparator.comparingLong(c -> c.candle().bucketStartTimeMsc())).toList()) {
            if (!bars.isEmpty() && bar.candle().bucketStartTimeMsc()<=bars.getLast().candle().bucketStartTimeMsc()) continue;
            if (bar.availableAtMsc()<bar.candle().bucketStartTimeMsc()+300000L)
                throw new IllegalArgumentException("Micro confirmation requires a closed M5 bar");
            bars.add(bar);
            if (bars.size()<3) {observeOpening();continue;}
            var left=bars.get(bars.size()-3).candle(); var origin=bars.get(bars.size()-2).candle(); var right=bar.candle();
            boolean top=origin.high()>left.high() && origin.high()>right.high();
            boolean bottom=origin.low()<left.low() && origin.low()<right.low();
            for (var type : StructuralLevel.Type.values()) {
                if (!(type==StructuralLevel.Type.TOP ? top : bottom)) continue;
                double price=type==StructuralLevel.Type.TOP ? origin.high() : origin.low();
                var p=new StructuralLevel(symbol+":5:micro:"+origin.bucketStartTimeMsc()+":"+type,type,price,
                    origin.bucketStartTimeMsc(),bar.availableAtMsc(),new StructuralLevel.Region(origin.high(),origin.low(),
                    (origin.high()+origin.low())/2,"PIVOT_CANDLE"),"MICRO_1_1");
                var previous=latest.get(type);
                String classification=previous==null ? "FIRST" : price==previous.price() ? "EQUAL"
                    : type==StructuralLevel.Type.TOP ? price>previous.price() ? "HH" : "LH" : price>previous.price() ? "HL" : "LL";
                boolean dual=top && bottom;
                String reason=dual ? "Dual extreme: intrabar order unknown; no edge" : "Strict experimental 1+1; right bar closed";
                pivots.add(new DowStructureEngine.Pivot(p,classification,previous==null?null:previous.id(),
                    previous==null?null:previous.price(),reason,dual?"LOCAL":"STRUCTURE"));
                events.add(new DowStructureEngine.Event(symbol+":micro:"+events.size(),p.confirmedAtMsc(),
                    dual?"AMBIGUOUS_PIVOT":"MICRO_PIVOT_CONFIRMED",p.id(),null,"UNDEFINED","UNDEFINED",reason,null,null));
                zigzag.accept(p,dual);
                if (dual) continue;
                latest.put(type,p);
            }
            observeOpening();
        }
        available=cutoff;
    }
    private OpeningWave.Snapshot opening() {
        return OpeningWave.analyze(symbol,bars,pivots.stream().filter(p->p.participation().equals("LOCAL")||zigzag.selectedIds().contains(p.extreme().id())).toList());
    }
    private void observeOpening() {
        var next=opening();if(openingHistory.isEmpty()||!openingHistory.getLast().equals(next))openingHistory.add(next);
    }
    public Snapshot snapshot() {
        return new Snapshot(symbol,5,available,"UNDEFINED","EXPERIMENTAL_MICRO_STRICT_1_1_V1",
            pivots.stream().map(p->new DowStructureEngine.Pivot(p.extreme(),p.classification(),p.comparisonId(),p.comparisonPrice(),p.reason(),zigzag.participation(p.extreme().id()))).toList(),
            zigzag.movements(),List.copyOf(events),opening(),zigzag.selectedIds(),zigzag.history(),zigzag.decisions(),List.copyOf(openingHistory));
    }
    public static Snapshot analyze(String symbol,List<CandleUpdate> candles,
            ThreeCandleStructure.Snapshot structure,long cutoff) {
        long day=structure.preparation()!=null ? java.time.LocalDate.parse(structure.preparation().sessionDate()).toEpochDay()
            : candles.isEmpty() ? -1 : candles.getLast().candle().bucketStartTimeMsc()/86400000L;
        var engine=new MicroDowEngine(symbol);
        engine.advance(candles.stream().filter(c -> c.candle().bucketStartTimeMsc()/86400000L==day).toList(),cutoff);
        return engine.snapshot();
    }
}
