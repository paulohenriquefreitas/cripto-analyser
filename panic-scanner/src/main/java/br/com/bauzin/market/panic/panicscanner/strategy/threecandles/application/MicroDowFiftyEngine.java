package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.StructuralLevel;
import java.util.*;

/** Read-only consumer of confirmed micro zigzag movements. No detection, opening anchors or trade rules. */
public final class MicroDowFiftyEngine {
    public record Wave(String id, String fromId, String toId, String direction,
                       StructuralLevel initial, StructuralLevel terminal,
                       double high, double low, double level50, long availableAtMsc, String state) {}
    public record Reference(Wave wave, Long supersededAtMsc, String supersededByWaveId) {}
    public record Event(String id, String kind, long atMsc, String waveId, String previousWaveId) {}
    public record Snapshot(String symbol, int timeframeMinutes, long availableAtMsc, String criterion,
                           String state, String currentWaveId, List<Reference> references, List<Event> events) {}
    private final String symbol;
    private final List<Reference> references = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String,Wave> seen = new HashMap<>();
    private long available = -1;

    public MicroDowFiftyEngine(String symbol) { this.symbol = Objects.requireNonNull(symbol); }

    public void advance(MicroDowEngine.Snapshot micro, long cutoff) {
        if (cutoff < available) throw new IllegalArgumentException("Rebuild micro 50 for a regressing cursor");
        if (!symbol.equals(micro.symbol()) || micro.timeframeMinutes()!=5)
            throw new IllegalArgumentException("Micro 50 requires the same symbol and M5");
        var pivots = new HashMap<String,StructuralLevel>();
        micro.pivots().forEach(p -> pivots.put(p.extreme().id(),p.extreme()));
        var decisions = new HashMap<String,MicroZigZag.Decision>();
        for (var d : micro.decisions()) if (d.movementId()!=null
                && Set.of("SEGMENT_CREATED","TERMINAL_REPLACED").contains(d.action())) decisions.put(d.movementId(),d);
        long known = Math.min(cutoff,micro.availableAtMsc());
        // Append-only history contains selected versions, including same-side extensions superseded later.
        // Do not filter old endpoints by their *current* STRUCTURE/SUPERSEDED participation.
        for (var m : micro.movementHistory()) {
            if (m.knownAtMsc()>known) continue;
            var a=pivots.get(m.fromId());var b=pivots.get(m.toId());var d=decisions.get(m.id());
            if (a==null || b==null || d==null || d.atMsc()!=m.knownAtMsc() || d.atMsc()>known
                    || !valid(a,known) || !valid(b,known) || a.type()==b.type()
                    || a.extremeTimeMsc()>=b.extremeTimeMsc()
                    || m.knownAtMsc()!=Math.max(a.confirmedAtMsc(),b.confirmedAtMsc())
                    || !m.id().equals(a.id()+"->"+b.id())
                    || !m.kind().equals(b.type()==StructuralLevel.Type.TOP?"UP":"DOWN")
                    || (m.kind().equals("UP") ? b.price()<=a.price() : b.price()>=a.price()))
                throw new IllegalArgumentException("Micro 50 requires a causally confirmed selected micro movement");
            var wave=new Wave(m.id(),a.id(),b.id(),m.kind(),a,b,Math.max(a.price(),b.price()),Math.min(a.price(),b.price()),
                (a.price()+b.price())/2,m.knownAtMsc(),"CONFIRMED");
            var existing=seen.get(wave.id());
            if (existing!=null) {
                if (!existing.equals(wave)) throw new IllegalArgumentException("A confirmed micro wave cannot change");
                continue;
            }
            var previous=references.isEmpty()?null:references.getLast();
            if (previous!=null && previous.wave().availableAtMsc()>wave.availableAtMsc())
                throw new IllegalArgumentException("Micro history must be chronological");
            if (previous!=null) references.set(references.size()-1,
                new Reference(previous.wave(),wave.availableAtMsc(),wave.id()));
            references.add(new Reference(wave,null,null));seen.put(wave.id(),wave);
            events.add(new Event(symbol+":micro50:"+events.size(),previous==null?"REFERENCE_CREATED":"REFERENCE_REPLACED",
                wave.availableAtMsc(),wave.id(),previous==null?null:previous.wave().id()));
        }
        available=cutoff;
    }
    private boolean valid(StructuralLevel p,long known) {
        return p.id().equals(symbol+":5:micro:"+p.extremeTimeMsc()+":"+p.type())
            && p.timeframeMinutes()==5 && "MICRO_1_1".equals(p.relevance())
            && p.confirmedAtMsc()>p.extremeTimeMsc() && p.confirmedAtMsc()<=known;
    }
    public Snapshot snapshot() {
        return new Snapshot(symbol,5,available,"CONFIRMED_MICRO_MOVEMENT_50_V1",
            references.isEmpty()?"WAITING_WAVE":"CONFIRMED",references.isEmpty()?null:references.getLast().wave().id(),
            List.copyOf(references),List.copyOf(events));
    }
    public static Snapshot analyze(MicroDowEngine.Snapshot micro,long cutoff) {
        var engine=new MicroDowFiftyEngine(micro.symbol());engine.advance(micro,cutoff);return engine.snapshot();
    }
}
