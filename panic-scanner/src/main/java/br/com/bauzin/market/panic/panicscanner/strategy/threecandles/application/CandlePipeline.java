package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Same ordered upsert/closure contract in both modes; no future storage here. */
public final class CandlePipeline {
    private final String symbol;
    private final int timeframe;
    private final ThreeCandleStructure structure;
    private final List<CandleUpdate> visible = new ArrayList<>();
    private final List<ThreeCandlePatternDetector> listeners = new ArrayList<>();
    public CandlePipeline(String symbol, int timeframe) { this.symbol = symbol; this.timeframe = timeframe; this.structure = new ThreeCandleStructure(timeframe); }
    public void subscribe(ThreeCandlePatternDetector listener) { listeners.add(Objects.requireNonNull(listener)); }
    public boolean accept(CandleUpdate next) {
        if (!next.candle().symbol().equals(symbol) || next.timeframeMinutes() != timeframe)
            throw new IllegalArgumentException("Cannot mix symbol/timeframe");
        if (!visible.isEmpty()) {
            var last = visible.getLast();
            long time = next.candle().bucketStartTimeMsc(), previous = last.candle().bucketStartTimeMsc();
            if (time < previous) throw new IllegalArgumentException("Regressing candle");
            if (time == previous) {
                if (last.equals(next)) return false;
                if (last.closed()) {
                    if (last.candle().equals(next.candle()) && next.closed()) return false;
                    throw new IllegalArgumentException("Cannot rewrite a closed candle");
                }
                if (next.availableAtMsc() < last.availableAtMsc()) return false;
                visible.set(visible.size()-1, next);
                notifyAccepted(next);
                return true;
            }
            if (!last.closed()) throw new IllegalArgumentException("Close current candle before appending");
        }
        visible.add(next); notifyAccepted(next); return true;
    }
    private void notifyAccepted(CandleUpdate next) {
        structure.onCandle(next);
        listeners.forEach(l -> l.onCandle(next));
    }
    public void rememberLevels(List<br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.StructuralLevel> levels) {
        structure.remember(levels);
    }
    public void prepare(H1SessionPreparation plan) { structure.prepare(plan); }
    public ThreeCandleStructure.Snapshot structure(int maxLevels) { return structure.snapshot(maxLevels); }
    public List<br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.StructuralLevel> activeLevels() {
        return structure.activeLevels();
    }
    public List<CandleUpdate> snapshot() { return List.copyOf(visible); }
}
