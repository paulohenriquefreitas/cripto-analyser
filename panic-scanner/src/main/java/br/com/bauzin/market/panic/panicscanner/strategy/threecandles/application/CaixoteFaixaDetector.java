package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.*;

/** Experimental price-only range model approved after the offline October comparison. */
public final class CaixoteFaixaDetector {
    public static final String CRITERIO = "FAIXA_CORPOS_EXPERIMENTAL_V1";
    private static final long M5 = 300_000L, DIA = 86_400_000L;
    private static final int WINDOW = 6, REFERENCE = 12;

    public record Version(long knownAtMsc, double topo, double fundo, double fibo50) {}
    public record Region(String id, long startTimeMsc, long endTimeMsc, long confirmedAtMsc,
                         double topo, double fundo, double fibo50, boolean active,
                         Long endedAtMsc, String exitReason, List<Version> versions) {
        public Region { versions = List.copyOf(versions); }
    }
    public record Diagnostico(double corpoMedio, double corpoReferencia, double rangeReferencia,
                              double larguraFechamentos, double inclinacaoPorCandle, double margem,
                              double larguraMaxima, int cruzamentosFormacao, int fechamentosFora) {}
    public record Snapshot(String symbol, long availableAtMsc, String criterio,
                           CaixoteDetector.Resultado atual, Diagnostico diagnostico, List<Region> regions) {
        public Snapshot { regions = List.copyOf(regions); }
    }

    private final String symbol;
    private final List<CandleUpdate> recent = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private CandleUpdate last;
    private Region active;
    private Diagnostico metrics;
    private int pendingSide, pendingCount, contained;
    private CaixoteDetector.Resultado atual = new CaixoteDetector.Resultado(CaixoteDetector.Estado.FORA_DO_CAIXOTE,
            null, null, null, null, null, 0, 0, null, null, 0, List.of("SEM_CANDLES"));

    public CaixoteFaixaDetector(String symbol) {
        if (symbol == null || !symbol.matches("WIN(?:\\$N|[A-Z][0-9]{2})"))
            throw new IllegalArgumentException("Expected a WIN series");
        this.symbol = symbol;
    }

    /** Rebuilds only the current session and known closed versions, matching the other chart studies. */
    public static Snapshot analyze(String symbol, List<CandleUpdate> source, long cutoff) {
        var detector = new CaixoteFaixaDetector(symbol);
        var bars = new TreeMap<Long, CandleUpdate>();
        long day = Math.floorDiv(cutoff, DIA);
        source.stream().filter(c -> c.closed() && c.timeframeMinutes() == 5
                && symbol.equals(c.candle().symbol()) && c.availableAtMsc() <= cutoff
                && Math.floorDiv(c.candle().bucketStartTimeMsc(), DIA) == day)
                .forEach(c -> bars.merge(c.candle().bucketStartTimeMsc(), c,
                        (a, b) -> a.availableAtMsc() >= b.availableAtMsc() ? a : b));
        bars.values().forEach(detector::onCandleFechado);
        return detector.snapshot(cutoff);
    }

    public Snapshot onCandleFechado(CandleUpdate update) {
        Objects.requireNonNull(update, "candle");
        if (!update.closed() || update.timeframeMinutes() != 5 || !symbol.equals(update.candle().symbol()))
            throw new IllegalArgumentException("One WIN series of closed M5 candles is required");
        long time = update.candle().bucketStartTimeMsc(), at = update.availableAtMsc();
        if (last != null) {
            long previous = last.candle().bucketStartTimeMsc();
            if (time == previous && update.candle().equals(last.candle())) return snapshot(last.availableAtMsc());
            if (time <= previous || at <= last.availableAtMsc())
                throw new IllegalArgumentException("Cannot rewrite or regress a closed candle");
            boolean newDay = Math.floorDiv(time, DIA) != Math.floorDiv(previous, DIA);
            if (newDay || time - previous != M5) {
                if (active != null) finish(last.availableAtMsc(), "DATA_GAP");
                if (newDay) regions.clear();
                recent.clear(); metrics = null; active = null;
                pendingSide = pendingCount = contained = 0;
            }
        }
        last = update;
        recent.add(update);
        if (recent.size() > WINDOW + REFERENCE) recent.removeFirst();
        if (active != null) {
            maintain(update);
        } else if (recent.size() < WINDOW + 6) {
            metrics = null;
            atual = result(null, null, null, 0, 0, null, null, List.of("AQUECIMENTO_FAIXA_12_CANDLES"));
        } else {
            consider();
        }
        return snapshot(at);
    }

    private void consider() {
        int split = recent.size() - WINDOW;
        var window = recent.subList(split, recent.size());
        var reference = recent.subList(0, split);
        double referenceBody = median(reference.stream().mapToDouble(c -> body(c)).toArray());
        double referenceRange = median(reference.stream().mapToDouble(c -> c.candle().high() - c.candle().low()).toArray());
        double[] closes = window.stream().mapToDouble(c -> c.candle().close()).toArray();
        double[] bodies = window.stream().mapToDouble(c -> body(c)).toArray();
        double high = Arrays.stream(closes).max().orElseThrow(), low = Arrays.stream(closes).min().orElseThrow();
        double center = Arrays.stream(closes).average().orElseThrow(), mid = (high + low) / 2;
        double slope = 0;
        for (int i = 0; i < WINDOW; i++) slope += (i - 2.5) * (closes[i] - center);
        slope /= 17.5;
        int side = 0, crossings = 0;
        for (double close : closes) {
            int next = Double.compare(close, mid);
            if (next == 0) continue;
            if (side != 0 && side != next) crossings++;
            side = next;
        }
        double average = Arrays.stream(bodies).average().orElseThrow(), padding = Arrays.stream(bodies).max().orElseThrow();
        double width = high - low, maxWidth = 1.65 * referenceRange;
        metrics = new Diagnostico(average, referenceBody, referenceRange, width, slope, padding, maxWidth, crossings, 0);
        var reasons = new ArrayList<String>();
        if (referenceBody <= 0 || referenceRange <= 0) reasons.add("REFERENCIA_FAIXA_INSUFICIENTE");
        if (average > .75 * referenceBody) reasons.add("CORPOS_SEM_CONTRACAO");
        if (width > referenceRange) reasons.add("FECHAMENTOS_DISPERSOS");
        if (Math.abs(slope) * 5 > referenceBody) reasons.add("CENTRO_DIRECIONAL");
        if (crossings < 2) reasons.add("MENOS_DE_2_CRUZAMENTOS");
        // A recovery followed by a new closing extreme is not a confirmed range.
        // Freeze the initial half before testing the final close; no future bars or tolerance.
        double initialLow = Math.min(closes[0], Math.min(closes[1], closes[2]));
        double initialHigh = Math.max(closes[0], Math.max(closes[1], closes[2]));
        if (closes[WINDOW - 1] < initialLow || closes[WINDOW - 1] > initialHigh)
            reasons.add("FECHAMENTO_FORA_NUCLEO_INICIAL");
        if (width + 2 * padding > maxWidth) reasons.add("ENVELOPE_EXCEDE_ORCAMENTO");
        if (low - padding <= 0) reasons.add("LIMITES_FAIXA_INVALIDOS");
        double top = high + padding, bottom = low - padding;
        long start = window.getFirst().candle().bucketStartTimeMsc();
        if (reasons.isEmpty()) {
            long at = last.availableAtMsc();
            active = new Region(symbol + ":faixa:" + at, start, at, at, top, bottom, mid, true, null, null,
                    List.of(new Version(at, top, bottom, mid)));
            regions.add(active);
            contained = WINDOW; pendingSide = pendingCount = 0;
        }
        atual = result(top, bottom, mid, active == null ? 0 : contained, crossings, start,
                active == null ? null : active.confirmedAtMsc(), reasons);
    }

    private void maintain(CandleUpdate update) {
        var bar = update.candle();
        long at = update.availableAtMsc();
        int side = bar.close() > active.topo() ? 1 : bar.close() < active.fundo() ? -1 : 0;
        pendingCount = side != 0 && side == pendingSide ? pendingCount + 1 : side == 0 ? 0 : 1;
        pendingSide = side;
        double top = active.topo(), bottom = active.fundo();
        var versions = new ArrayList<>(active.versions());
        if (side == 0) {
            contained++;
            double slack = Math.max(0, metrics.larguraMaxima() - (top - bottom));
            double up = Math.max(0, bar.high() - top), down = Math.max(0, bottom - bar.low());
            double scale = up + down > 0 ? Math.min(1, slack / (up + down)) : 0;
            top += up * scale; bottom -= down * scale;
            if (top != active.topo() || bottom != active.fundo())
                versions.add(new Version(at, top, bottom, (top + bottom) / 2));
        }
        active = new Region(active.id(), active.startTimeMsc(), at, active.confirmedAtMsc(), top, bottom,
                (top + bottom) / 2, true, null, null, versions);
        regions.set(regions.size() - 1, active);
        metrics = new Diagnostico(metrics.corpoMedio(), metrics.corpoReferencia(), metrics.rangeReferencia(),
                metrics.larguraFechamentos(), metrics.inclinacaoPorCandle(), metrics.margem(),
                metrics.larguraMaxima(), metrics.cruzamentosFormacao(), pendingCount);
        var known = active;
        if (pendingCount >= 2) {
            finish(at, side > 0 ? "M5_TWO_CLOSES_ABOVE" : "M5_TWO_CLOSES_BELOW");
            recent.clear(); recent.add(update);
            pendingSide = pendingCount = contained = 0;
            atual = result(top, bottom, known.fibo50(), 0, metrics.cruzamentosFormacao(), known.startTimeMsc(), null,
                    List.of("ROMPIMENTO_2_FECHAMENTOS"));
        } else {
            atual = result(top, bottom, known.fibo50(), contained, metrics.cruzamentosFormacao(), known.startTimeMsc(),
                    known.confirmedAtMsc(), side == 0 ? List.of() : List.of("ROMPIMENTO_EM_AVALIACAO"));
        }
    }

    private void finish(long at, String reason) {
        regions.set(regions.size() - 1, new Region(active.id(), active.startTimeMsc(), at, active.confirmedAtMsc(),
                active.topo(), active.fundo(), active.fibo50(), false, at, reason, active.versions()));
        active = null;
    }

    private CaixoteDetector.Resultado result(Double top, Double bottom, Double mid, int count, int crossings,
                                             Long start, Long confirmed, List<String> reasons) {
        return new CaixoteDetector.Resultado(active == null ? CaixoteDetector.Estado.FORA_DO_CAIXOTE : CaixoteDetector.Estado.CAIXOTE,
                top, bottom, mid, top == null ? null : top - bottom, null, count, crossings, start, confirmed,
                last == null ? 0 : last.availableAtMsc(), reasons);
    }
    private static double body(CandleUpdate c) { return Math.abs(c.candle().close() - c.candle().open()); }
    private static double median(double[] a) { Arrays.sort(a); return (a[(a.length - 1) / 2] + a[a.length / 2]) / 2; }
    public Snapshot snapshot(long cutoff) { return new Snapshot(symbol, cutoff, CRITERIO, atual, metrics, regions); }
}
