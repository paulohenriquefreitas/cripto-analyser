package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import java.util.*;

/** Experimental M5 observer. Consumes detector output; never detects pivots or drives a strategy. */
public final class DowStructureEngine {
    public record Pivot(StructuralLevel extreme, String classification, String comparisonId,
                        Double comparisonPrice, String reason, String participation) {}
    public record Movement(String id, String fromId, String toId, long knownAtMsc, String kind) {}
    public record Event(String id, long atMsc, String kind, String pivotId, String affectedMovementId,
                        String stateBefore, String stateAfter, String reason, Long candleTimeMsc, Double observedClose) {}
    public record Snapshot(String symbol, int timeframeMinutes, long availableAtMsc, String state,
                           String criterion, List<Pivot> pivots, List<Movement> movements, List<Event> events) {}
    private final String symbol;
    private final List<Pivot> pivots = new ArrayList<>();
    private final List<StructuralLevel> chain = new ArrayList<>();
    private final Map<StructuralLevel.Type, Pivot> latest = new EnumMap<>(StructuralLevel.Type.class);
    private final Set<String> seen = new HashSet<>(), broken = new HashSet<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String,String> lastMovementByPivot = new HashMap<>();
    private String state = "UNDEFINED", trendPair = "";
    private StructuralLevel protectedLow, protectedHigh;
    private long available = -1;

    public DowStructureEngine(String symbol) { this.symbol = Objects.requireNonNull(symbol); }

    /** Call with successive known prefixes. Repeated observations are idempotent. */
    public void advance(List<StructuralLevel> originals, List<CandleUpdate> candles, long cutoff) {
        if (cutoff < available) throw new IllegalArgumentException("Rebuild Dow for a regressing cursor");
        var pending = originals.stream().filter(p -> p.timeframeMinutes() == 5 && p.id().startsWith(symbol+":5:")
                && p.confirmedAtMsc() <= cutoff && !seen.contains(p.id()))
            .sorted(Comparator.comparingLong(StructuralLevel::confirmedAtMsc)
                .thenComparingLong(StructuralLevel::extremeTimeMsc).thenComparing(StructuralLevel::id)).toList();
        var bars = candles.stream().filter(c -> c.closed() && c.timeframeMinutes() == 5
                && c.candle().symbol().equals(symbol) && c.availableAtMsc() <= cutoff && c.availableAtMsc() > available)
            .sorted(Comparator.comparingLong(CandleUpdate::availableAtMsc)).toList();
        int index = 0;
        for (var bar : bars) {
            while (index < pending.size() && pending.get(index).confirmedAtMsc() < bar.availableAtMsc())
                accept(pending.get(index++), pending);
            observeClose(bar); // Newly confirmed pivots cannot explain a break inside this bar.
            while (index < pending.size() && pending.get(index).confirmedAtMsc() == bar.availableAtMsc())
                accept(pending.get(index++), pending);
        }
        while (index < pending.size()) accept(pending.get(index++), pending);
        available = cutoff;
    }

    private void accept(StructuralLevel p, List<StructuralLevel> batch) {
        seen.add(p.id());
        if (p.confirmedAtMsc() <= p.extremeTimeMsc()) throw new IllegalArgumentException("Unconfirmed Dow pivot");
        // State/brokenAt belong to the level observer, not Dow, and may describe a later instant.
        p = new StructuralLevel(p.id(),p.type(),p.price(),p.extremeTimeMsc(),p.confirmedAtMsc(),p.region(),
            p.relevance(),StructuralLevel.State.CONFIRMED,null,5);
        var previous = latest.get(p.type());
        String classification = previous == null ? "FIRST" : p.price() == previous.extreme().price() ? "EQUAL"
            : p.type() == StructuralLevel.Type.TOP ? p.price() > previous.extreme().price() ? "HH" : "LH"
            : p.price() > previous.extreme().price() ? "HL" : "LL";
        final var pivot = p;
        boolean dual = batch.stream().anyMatch(other -> other.extremeTimeMsc() == pivot.extremeTimeMsc()
            && other.confirmedAtMsc() == pivot.confirmedAtMsc() && other.type() != pivot.type());
        var terminal = chain.isEmpty() ? null : chain.getLast();
        boolean older = terminal != null && p.extremeTimeMsc() <= terminal.extremeTimeMsc();
        boolean sameSide = terminal != null && terminal.type() == p.type();
        boolean extendsTerminal = sameSide && (p.type() == StructuralLevel.Type.TOP ? p.price() > terminal.price() : p.price() < terminal.price());
        boolean used = !dual && !older && (!sameSide || extendsTerminal);
        String reason = dual ? "Top and bottom in the same candle: intrabar order unknown"
            : older ? "Origin does not follow the current structural terminal"
            : sameSide && !extendsTerminal ? "Local same-side pivot does not extend the terminal"
            : extendsTerminal ? "Confirmed same-side extension replaces the structural terminal"
            : "Confirmed alternating pivot; strict comparison with the preceding structural extreme of the same type";
        var decision = new Pivot(p,classification,previous == null ? null : previous.extreme().id(),
            previous == null ? null : previous.extreme().price(),reason,used ? "STRUCTURE" : "LOCAL");
        pivots.add(decision);
        emit(p.confirmedAtMsc(),"PIVOT_DETECTED",p.id(),null,state,state,reason);
        if (!used) return;
        String affected = chain.size() < 2 ? null : movement(chain.get(chain.size()-2),chain.getLast()).id();
        if (extendsTerminal) chain.removeLast();
        chain.add(p); latest.put(p.type(),decision);
        if(chain.size()>1) {
            var m=movement(chain.get(chain.size()-2),p);
            lastMovementByPivot.put(m.fromId(),m.id());lastMovementByPivot.put(m.toId(),m.id());
        }
        emit(p.confirmedAtMsc(),extendsTerminal ? "TERMINAL_EXTENDED" : "STRUCTURE_PIVOT_SELECTED",p.id(),affected,state,state,reason);
        if (state.equals("UP") && classification.equals("LH") || state.equals("DOWN") && classification.equals("HL"))
            emit(p.confirmedAtMsc(),"POSSIBLE_REVERSAL",p.id(),affected,state,state,"Opposing pivot alone does not reverse the trend");
        confirmTrend(p);
        if (state.equals("UP") && classification.equals("HL")) protectedLow = p;
        if (state.equals("DOWN") && classification.equals("LH")) protectedHigh = p;
    }

    private void confirmTrend(StructuralLevel p) {
        var high=latest.get(StructuralLevel.Type.TOP); var low=latest.get(StructuralLevel.Type.BOTTOM);
        if (high == null || low == null) return;
        String next = high.classification().equals("HH") && low.classification().equals("HL") ? "UP"
            : high.classification().equals("LH") && low.classification().equals("LL") ? "DOWN" : null;
        String pair=high.extreme().id()+"/"+low.extreme().id();
        if (next == null || pair.equals(trendPair)) return;
        if (next.equals("UP") && broken.contains(low.extreme().id())
                || next.equals("DOWN") && broken.contains(high.extreme().id())) return;
        trendPair=pair;
        if (!next.equals(state)) {
            String before=state;state=next;
            protectedLow=low.extreme();protectedHigh=high.extreme();
            emit(p.confirmedAtMsc(),"TREND_CONFIRMED",p.id(),null,before,state,"Two rising highs/lows or two falling highs/lows; no amplitude threshold");
        }
    }

    private void observeClose(CandleUpdate bar) {
        for (var ref : new StructuralLevel[]{latest.containsKey(StructuralLevel.Type.TOP) ? latest.get(StructuralLevel.Type.TOP).extreme() : null,
                latest.containsKey(StructuralLevel.Type.BOTTOM) ? latest.get(StructuralLevel.Type.BOTTOM).extreme() : null,
                protectedHigh,protectedLow}) {
            if (ref == null || broken.contains(ref.id()) || ref.confirmedAtMsc() > bar.candle().bucketStartTimeMsc()) continue;
            boolean above=ref.type()==StructuralLevel.Type.TOP;
            if (!(above ? bar.candle().close()>ref.price() : bar.candle().close()<ref.price())) continue;
            broken.add(ref.id());
            String affected=lastMovementByPivot.get(ref.id());
            events.add(new Event(symbol+":dow:"+events.size(),bar.availableAtMsc(),above?"RESISTANCE_BROKEN":"SUPPORT_BROKEN",ref.id(),affected,state,state,
                "Fechamento M5 em "+bar.candle().close()+" rompeu a referência confirmada "+ref.price()+"; pavio isolado não basta",
                bar.candle().bucketStartTimeMsc(),bar.candle().close()));
            if (state.equals("UP") && protectedLow != null && ref.id().equals(protectedLow.id())
                    || state.equals("DOWN") && protectedHigh != null && ref.id().equals(protectedHigh.id())) {
                String before=state;state="TRANSITION";
                emit(bar.availableAtMsc(),"POSSIBLE_REVERSAL",ref.id(),affected,before,state,"Protected structural extreme broken by a closed candle; opposite trend still needs paired pivots");
            }
        }
    }

    private Movement movement(StructuralLevel a, StructuralLevel b) {
        return new Movement(a.id()+"→"+b.id(),a.id(),b.id(),Math.max(a.confirmedAtMsc(),b.confirmedAtMsc()),b.type()==StructuralLevel.Type.TOP?"UP":"DOWN");
    }
    private List<Movement> movements() {
        var result=new ArrayList<Movement>();for(int i=1;i<chain.size();i++)result.add(movement(chain.get(i-1),chain.get(i)));return List.copyOf(result);
    }
    private void emit(long at,String kind,String pivot,String movement,String before,String after,String reason) {
        events.add(new Event(symbol+":dow:"+events.size(),at,kind,pivot,movement,before,after,reason,null,null));
    }
    public Snapshot snapshot() {
        var used=new HashSet<String>();chain.forEach(p -> used.add(p.id()));
        return new Snapshot(symbol,5,available,state,"EXPERIMENTAL_PAIRED_PIVOTS_CLOSE_BREAK_V1",
            pivots.stream().map(p -> new Pivot(p.extreme(),p.classification(),p.comparisonId(),p.comparisonPrice(),p.reason(),
                used.contains(p.extreme().id()) ? "STRUCTURE" : p.participation().equals("STRUCTURE") ? "SUPERSEDED" : "LOCAL")).toList(),
            movements(),List.copyOf(events));
    }
    public static Snapshot analyze(String symbol, List<CandleUpdate> candles, ThreeCandleStructure.Snapshot structure, long cutoff) {
        long day=structure.preparation() != null ? java.time.LocalDate.parse(structure.preparation().sessionDate()).toEpochDay()
            : candles.isEmpty() ? -1 : candles.getLast().candle().bucketStartTimeMsc()/86400000L;
        var engine=new DowStructureEngine(symbol);
        engine.advance(structure.confirmedPivots().stream().filter(p -> p.extremeTimeMsc()/86400000L==day).toList(),
            candles.stream().filter(c -> c.candle().bucketStartTimeMsc()/86400000L==day).toList(),cutoff);
        return engine.snapshot();
    }
}
