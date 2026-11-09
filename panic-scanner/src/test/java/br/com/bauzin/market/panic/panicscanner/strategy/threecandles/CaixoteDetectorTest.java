package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.CaixoteDetector;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.CaixoteDetector.Estado.*;
import static org.assertj.core.api.Assertions.*;

class CaixoteDetectorTest {
    static final long BASE = LocalDate.parse("2026-10-07").toEpochDay() * 86_400_000L + 9 * 3_600_000L;

    static CandleUpdate bar(int i, double open, double high, double low, double close) {
        long at = BASE + i * 300_000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WIN$N", at, open, high, low, close), 5, true, at + 300_000L);
    }

    static List<CandleUpdate> box(double... closes) {
        var bars = new ArrayList<CandleUpdate>();
        for (int i = 0; i < 14; i++) bars.add(bar(i, 100, 150, 50, 100));
        bars.add(bar(14, 102, 105, 95, 102));
        for (int i = 0; i < closes.length; i++) bars.add(bar(15 + i, 100, 104, 96, closes[i]));
        return bars;
    }

    static List<CandleUpdate> box() {
        return box(98, 102, 98, 102, 98, 102);
    }

    @Test void requiresSixTestBarsAndTwentyTrueRangesThenFreezesBox() {
        var bars = box();
        var detector = new CaixoteDetector();
        for (int i = 0; i < 20; i++) {
            assertThat(detector.onCandleFechado(bars.get(i)).estado()).isEqualTo(FORA_DO_CAIXOTE);
        }
        var result = detector.onCandleFechado(bars.getLast());
        assertThat(result.estado()).isEqualTo(CAIXOTE);
        assertThat(result.topo()).isEqualTo(105);
        assertThat(result.fundo()).isEqualTo(95);
        assertThat(result.fibo50()).isEqualTo(100);
        assertThat(result.atr20()).isCloseTo(67.9, within(0.000001));
        assertThat(result.candlesNoCaixote()).isEqualTo(6);
        assertThat(result.cruzamentos50()).isEqualTo(5);
        assertThat(result.confirmadoEmMsc()).isEqualTo(bars.getLast().availableAtMsc());
        for (int i = 21; i < 60; i++) {
            result = detector.onCandleFechado(bar(i, 100, 105, 95, 100));
            assertThat(result.estado()).isEqualTo(CAIXOTE);
            assertThat(result.topo()).isEqualTo(105);
            assertThat(result.fundo()).isEqualTo(95);
        }
        assertThat(result.atr20()).isEqualTo(10); // Activation contraction isn't reapplied to an established range.
    }

    @Test void midpointTouchesAreNotCrossingsAndAnEqualityPlateauCountsOnlyOnce() {
        var touches = CaixoteDetector.analisar(box(98,100,98,100,98,100));
        assertThat(touches.cruzamentos50()).isZero();
        assertThat(touches.estado()).isEqualTo(FORA_DO_CAIXOTE);
        var plateau = CaixoteDetector.analisar(box(98,100,102,100,98,98));
        assertThat(plateau.cruzamentos50()).isEqualTo(2);
        assertThat(plateau.estado()).isEqualTo(CAIXOTE);
        assertThat(CaixoteDetector.analisar(box(98,98,98,98,98,98)).cruzamentos50()).isZero();
    }

    @Test void aWickOutsideTheAnchorInvalidatesContainmentEvenIfTheCloseReturns() {
        var bars = new ArrayList<>(box());
        bars.set(15, bar(15, 100, 105.5, 96, 98));
        var result = CaixoteDetector.analisar(bars);
        assertThat(result.estado()).isEqualTo(FORA_DO_CAIXOTE);
        assertThat(result.motivos()).contains("LIMITES_ROMPIDOS");
    }

    @Test void exitsOnWickAndStartsANewAnchorWithItsOwnMidpoint() {
        var detector = new CaixoteDetector();
        box().forEach(detector::onCandleFechado);
        var broken = detector.onCandleFechado(bar(21, 105.2, 106, 105, 105.8));
        assertThat(broken.estado()).isEqualTo(FORA_DO_CAIXOTE);
        assertThat(broken.motivos()).containsExactly("ROMPIMENTO");
        assertThat(broken.fibo50()).isEqualTo(105.5);
        for (int i = 22; i <= 27; i++) {
            var r = detector.onCandleFechado(bar(i, 105.5, 105.9, 105.1, i % 2 == 0 ? 105.2 : 105.8));
            assertThat(r.estado()).isEqualTo(i == 27 ? CAIXOTE : FORA_DO_CAIXOTE);
            if (i < 27) assertThat(r.motivos()).contains("AGUARDANDO_6_CANDLES_CONTIDOS");
        }
        assertThat(detector.resultado().topo()).isEqualTo(106);
        assertThat(detector.resultado().fundo()).isEqualTo(105);
    }

    @Test void includesPreviousCloseInTrueRangeAndUsesStrictHeightThreshold() {
        var bars = new ArrayList<CandleUpdate>();
        for (int i = 0; i < 20; i++) bars.add(bar(i, 100, 101, 99, 100));
        bars.add(bar(20, 200, 201, 199, 200));
        assertThat(CaixoteDetector.analisar(bars).atr20()).isCloseTo(6.95, within(0.000001));
        bars.clear();
        for (int i = 0; i < 13; i++) bars.add(bar(i, 100, 106, 94, 100));
        bars.add(bar(13, 100, 112, 88, 100));
        bars.add(bar(14, 100, 104, 96, 100));
        for (int i = 15; i <= 20; i++) bars.add(bar(i, 100, 102, 98, i % 2 == 0 ? 99 : 101));
        var result = CaixoteDetector.analisar(bars);
        assertThat(result.atr20()).isEqualTo(10);
        assertThat(result.altura()).isEqualTo(8);
        assertThat(result.estado()).isEqualTo(FORA_DO_CAIXOTE);
        assertThat(result.motivos()).contains("ALTURA_NAO_MENOR_QUE_80_PORCENTO_ATR20");
    }

    @Test void resetsAtGapOrNewSessionAndDoesNotBridgeMissingCandles() {
        for (int next : new int[]{22, 288}) {
            var detector = new CaixoteDetector();
            box().forEach(detector::onCandleFechado);
            var result = detector.onCandleFechado(bar(next, 100, 104, 96, 100));
            assertThat(result.estado()).isEqualTo(FORA_DO_CAIXOTE);
            assertThat(result.atr20()).isNull();
            assertThat(result.confirmadoEmMsc()).isNull();
        }
    }

    @Test void duplicateIsIdempotentAndInvalidObservationsDoNotMutateState() {
        var bars = box();
        var detector = new CaixoteDetector();
        bars.forEach(detector::onCandleFechado);
        var before = detector.resultado();
        assertThat(detector.onCandleFechado(bars.getLast())).isSameAs(before);
        assertThatThrownBy(() -> detector.onCandleFechado(bar(20,100,104,96,99)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> detector.onCandleFechado(bars.getFirst())).isInstanceOf(IllegalArgumentException.class);
        var forming = new CandleUpdate(bar(21,100,104,96,99).candle(),5,false,BASE+21*300_000L);
        assertThatThrownBy(() -> detector.onCandleFechado(forming)).isInstanceOf(IllegalArgumentException.class);
        var other = new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",BASE+21*300_000L,100,104,96,99),5,true,BASE+22*300_000L);
        assertThatThrownBy(() -> detector.onCandleFechado(other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(detector.resultado()).isSameAs(before);
    }

    @Test void batchStreamingAndCutoffAgreeForEveryPrefix() {
        var bars = box();
        var detector = new CaixoteDetector();
        for (int i = 0; i < bars.size(); i++) {
            var known = detector.onCandleFechado(bars.get(i));
            assertThat(CaixoteDetector.analisar(bars.subList(0,i+1))).isEqualTo(known);
            assertThat(CaixoteDetector.analisar(bars,bars.get(i).availableAtMsc())).isEqualTo(known);
        }
        var empty = CaixoteDetector.analisar(List.of());
        assertThat(empty.estado()).isEqualTo(FORA_DO_CAIXOTE);
        assertThat(empty.topo()).isNull();
        assertThat(empty.fibo50()).isNull();
    }
}
