package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Causal display history of the existing CaixoteDetector, without changing its activation rules. */
public final class CaixoteMarketStudy {
    private CaixoteMarketStudy() {}

    public record Region(String id, long startTimeMsc, long endTimeMsc, long confirmedAtMsc,
                         double topo, double fundo, double fibo50, boolean active,
                         Long endedAtMsc, String exitReason) {}
    public record Snapshot(String symbol, long availableAtMsc, CaixoteDetector.Resultado atual,
                           List<Region> regions) {}

    public static Snapshot analyze(String symbol, List<CandleUpdate> source, long cutoff) {
        long day = Math.floorDiv(cutoff, 86_400_000L);
        var bars = new TreeMap<Long, CandleUpdate>();
        source.stream().filter(c -> c.closed() && c.timeframeMinutes() == 5
                && symbol.equals(c.candle().symbol()) && c.availableAtMsc() <= cutoff
                && Math.floorDiv(c.candle().bucketStartTimeMsc(), 86_400_000L) == day)
                .forEach(c -> bars.merge(c.candle().bucketStartTimeMsc(), c,
                        (a, b) -> a.availableAtMsc() >= b.availableAtMsc() ? a : b));
        var detector = new CaixoteDetector();
        var regions = new ArrayList<Region>();
        Region active = null;
        CandleUpdate previous = null;
        for (var bar : bars.values()) {
            long at = bar.availableAtMsc();
            if (active != null && previous != null
                    && bar.candle().bucketStartTimeMsc() - previous.candle().bucketStartTimeMsc() != 300_000L) {
                regions.set(regions.size() - 1, finish(active, previous.availableAtMsc(), "DATA_GAP"));
                active = null;
            }
            var result = detector.onCandleFechado(bar);
            if (result.estado() == CaixoteDetector.Estado.CAIXOTE) {
                if (active == null) {
                    active = new Region(symbol + ":caixote:" + result.confirmadoEmMsc(),
                            result.inicioObservadoMsc(), at, result.confirmadoEmMsc(),
                            result.topo(), result.fundo(), result.fibo50(), true, null, null);
                    regions.add(active);
                } else {
                    active = new Region(active.id(), active.startTimeMsc(), at, active.confirmedAtMsc(),
                            active.topo(), active.fundo(), active.fibo50(), true, null, null);
                    regions.set(regions.size() - 1, active);
                }
            } else if (active != null) {
                boolean above = bar.candle().high() > active.topo();
                boolean below = bar.candle().low() < active.fundo();
                regions.set(regions.size() - 1, finish(active, at, above && below ? "M5_WICK_BOTH"
                        : above ? "M5_WICK_ABOVE" : "M5_WICK_BELOW"));
                active = null;
            }
            previous = bar;
        }
        return new Snapshot(symbol, cutoff, detector.resultado(), List.copyOf(regions));
    }

    private static Region finish(Region r, long at, String reason) {
        return new Region(r.id(), r.startTimeMsc(), at, r.confirmedAtMsc(), r.topo(), r.fundo(),
                r.fibo50(), false, at, reason);
    }
}
