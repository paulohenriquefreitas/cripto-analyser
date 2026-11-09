package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Price-only, closed-M5 detector. An anchor candle establishes immutable bounds;
 * the following six candles must stay inside them. A confirmed box stays active
 * until a subsequent candle's high/low breaks a bound (equality is contained).
 * ATR20 is the arithmetic mean of twenty true ranges, requiring 21 known bars.
 * Instances belong to one ordered WIN stream; no trading or Dow dependencies.
 */
public final class CaixoteDetector {
    public enum Estado { CAIXOTE, FORA_DO_CAIXOTE }

    public record Resultado(Estado estado, Double topo, Double fundo, Double fibo50,
                            Double altura, Double atr20, int candlesNoCaixote,
                            int cruzamentos50, Long inicioObservadoMsc,
                            Long confirmadoEmMsc, long availableAtMsc, List<String> motivos) {
        public Resultado {
            motivos = List.copyOf(motivos);
        }
    }

    private static final int JANELA = 6;
    private static final int PERIODO_ATR = 20;
    private static final double FATOR_APERTO = 0.8;
    private static final long M5 = 300_000L;
    private static final long DIA = 86_400_000L;
    private final ArrayDeque<CandleUpdate> historico = new ArrayDeque<>();
    private final ArrayDeque<CandleUpdate> candidata = new ArrayDeque<>();
    private CandleUpdate ultimo;
    private Resultado resultado = new Resultado(Estado.FORA_DO_CAIXOTE, null, null, null,
            null, null, 0, 0, null, null, 0, List.of("SEM_CANDLES"));
    private boolean ativo;
    private double topo, fundo;
    private int contidos;
    private Long inicio, confirmadoEm;

    /** Batch and streaming share exactly the same state machine. Input must contain closed M5 bars. */
    public static Resultado analisar(List<CandleUpdate> candles) {
        Objects.requireNonNull(candles, "candles");
        var detector = new CaixoteDetector();
        for (var candle : candles) detector.onCandleFechado(candle);
        return detector.resultado();
    }

    /** Replay prefix, excluding observations not yet available at the requested instant. */
    public static Resultado analisar(List<CandleUpdate> candles, long availableAtMsc) {
        Objects.requireNonNull(candles, "candles");
        return analisar(candles.stream().filter(c -> Objects.requireNonNull(c, "candle").availableAtMsc()
                <= availableAtMsc).toList());
    }

    public Resultado onCandleFechado(CandleUpdate candle) {
        validar(candle);
        long time = candle.candle().bucketStartTimeMsc();
        if (ultimo != null) {
            long previous = ultimo.candle().bucketStartTimeMsc();
            if (time == previous) {
                if (candle.candle().equals(ultimo.candle())) return resultado;
                throw new IllegalArgumentException("Cannot rewrite a closed candle");
            }
            if (time < previous || candle.availableAtMsc() <= ultimo.availableAtMsc()) {
                throw new IllegalArgumentException("Candles must have increasing origins and availability");
            }
            if (Math.floorDiv(time, DIA) != Math.floorDiv(previous, DIA) || time - previous != M5) {
                limparSessao();
            }
        }
        ultimo = candle;
        historico.addLast(candle);
        if (historico.size() > PERIODO_ATR + 1) historico.removeFirst();
        Double atr = atr20();

        if (ativo) {
            if (rompe(candle, topo, fundo)) {
                ativo = false;
                candidata.clear();
                candidata.addLast(candle); // Break candle anchors a fresh candidate, never a same-bar re-entry.
                topo = candle.candle().high();
                fundo = candle.candle().low();
                contidos = 0;
                inicio = time;
                confirmadoEm = null;
                return publicar(atr, 0, List.of("ROMPIMENTO"));
            }
            adicionarCandidata(candle);
            contidos++;
            return publicar(atr, cruzamentos(new ArrayList<>(candidata), meio()), List.of());
        }

        adicionarCandidata(candle);
        var window = new ArrayList<>(candidata);
        var anchor = window.getFirst().candle();
        topo = anchor.high();
        fundo = anchor.low();
        inicio = anchor.bucketStartTimeMsc();
        contidos = 0;
        boolean contained = true;
        for (int i = 1; i < window.size(); i++) {
            if (rompe(window.get(i), topo, fundo)) {
                contained = false;
                contidos = 0;
            } else {
                contidos++;
            }
        }
        int crossings = cruzamentos(window, meio());
        var reasons = new ArrayList<String>();
        if (window.size() < JANELA + 1) reasons.add("AGUARDANDO_6_CANDLES_CONTIDOS");
        if (atr == null) reasons.add("AQUECIMENTO_ATR20");
        if (!contained) reasons.add("LIMITES_ROMPIDOS");
        if (atr != null && (!(topo > fundo) || !(topo - fundo < FATOR_APERTO * atr))) {
            reasons.add("ALTURA_NAO_MENOR_QUE_80_PORCENTO_ATR20");
        }
        if (crossings < 2) reasons.add("MENOS_DE_2_CRUZAMENTOS");
        if (reasons.isEmpty()) {
            ativo = true;
            confirmadoEm = candle.availableAtMsc();
        }
        return publicar(atr, crossings, reasons);
    }

    private void validar(CandleUpdate candle) {
        Objects.requireNonNull(candle, "candle");
        if (!candle.closed() || candle.timeframeMinutes() != 5) {
            throw new IllegalArgumentException("Only closed M5 candles are accepted");
        }
        String symbol = candle.candle().symbol();
        if (!symbol.matches("WIN(?:\\$N|[A-Z][0-9]{2})")) {
            throw new IllegalArgumentException("Expected a WIN symbol");
        }
        if (ultimo != null && !symbol.equals(ultimo.candle().symbol())) {
            throw new IllegalArgumentException("Cannot mix WIN series in one detector");
        }
    }

    private void adicionarCandidata(CandleUpdate candle) {
        candidata.addLast(candle);
        if (candidata.size() > JANELA + 1) candidata.removeFirst();
    }

    private static boolean rompe(CandleUpdate c, double high, double low) {
        return c.candle().high() > high || c.candle().low() < low;
    }

    /** Only the six test closes participate: no crossing imported from the anchor candle. */
    private static int cruzamentos(List<CandleUpdate> window, double mid) {
        int first = Math.max(1, window.size() - JANELA);
        int side = 0, count = 0;
        for (int i = first; i < window.size(); i++) {
            double close = window.get(i).candle().close();
            int next = Double.compare(close, mid);
            if (next == 0) continue; // Touches/plateaus are not crossings; preserve the last strict side.
            if (side != 0 && side != next) count++;
            side = next;
        }
        return count;
    }

    private Double atr20() {
        if (historico.size() < PERIODO_ATR + 1) return null;
        var bars = new ArrayList<>(historico);
        double sum = 0;
        for (int i = 1; i < bars.size(); i++) {
            var c = bars.get(i).candle();
            double previousClose = bars.get(i - 1).candle().close();
            sum += Math.max(c.high() - c.low(),
                    Math.max(Math.abs(c.high() - previousClose), Math.abs(c.low() - previousClose)));
        }
        return sum / PERIODO_ATR;
    }

    private double meio() {
        return fundo + (topo - fundo) / 2;
    }

    private Resultado publicar(Double atr, int crossings, List<String> reasons) {
        resultado = new Resultado(ativo ? Estado.CAIXOTE : Estado.FORA_DO_CAIXOTE,
                topo, fundo, meio(), topo - fundo, atr, contidos, crossings,
                inicio, confirmadoEm, ultimo.availableAtMsc(), reasons);
        return resultado;
    }

    private void limparSessao() {
        historico.clear();
        candidata.clear();
        ativo = false;
        contidos = 0;
        inicio = null;
        confirmadoEm = null;
    }

    public Resultado resultado() {
        return resultado;
    }
}
