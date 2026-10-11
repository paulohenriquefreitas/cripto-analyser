package br.com.bauzin.market.panic.panicscanner.application.win.ml;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MlCandleDatasetTest {
    private static final LocalDate DATE = LocalDate.of(2026, 4, 6);
    private static final long START = DATE.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L;
    private long sequence;
    private MlCandleDataset engine(LocalDate date, long start) {
        var e = new MlCandleDataset("WIN$N", date, ZoneId.of("America/Sao_Paulo"), 1.0);
        List<Mt5Candle> seed = new ArrayList<>();
        for (int i = 30; i > 0; i--) seed.add(new Mt5Candle((start - i * MlCandleDataset.M5) / 1000, 100, 105, 95, 100, 0, 0));
        e.warmUp(new Mt5HistoricalPriceSource.Header("WIN$N", start, start + 86_400_000L, seed, List.of(), 2));
        return e;
    }
    private void tick(MlCandleDataset e, long time, double price) {
        e.onLast(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent("WIN$N", time, price), 1, 8, sequence++));
    }
    private void bar(MlCandleDataset e, long start, double close) {
        tick(e, start, 100); tick(e, start + 1, Math.max(105, close));
        tick(e, start + 2, Math.min(95, close)); tick(e, start + 3, close);
    }
    private MlCandleDataset.Feature anchor(MlCandleDataset.Session s, int i) { return s.candles().get(i); }
    private String value(MlCandleDataset.Feature f, String name) { return f.values().get(MlCandleDataset.featureColumns().indexOf(name)); }

    @Test void futureLastPricesAndIncompleteCandleNeverChangeClosedFeatures() {
        var a = engine(DATE, START); var b = engine(DATE, START);
        for (int i = 0; i <= 20; i++) { bar(a, START + i * 300_000L, 100); bar(b, START + i * 300_000L, 100); }
        // The first future LAST closes the anchor, but belongs exclusively to labels/new candle.
        tick(a, START + 21 * 300_000L, 1000); tick(b, START + 21 * 300_000L, 1);
        for (int i = 22; i <= 24; i++) { bar(a, START + i * 300_000L, 130); bar(b, START + i * 300_000L, 70); }
        var sa = a.finish(); var sb = b.finish();
        assertThat(anchor(sa, 20)).isEqualTo(anchor(sb, 20));
        assertThat(value(anchor(sa, 20), "ema9")).isEqualTo("100.0");
        assertThat(Double.parseDouble(value(anchor(sa, 20), "atr14"))).isCloseTo(10, within(1e-10));
        assertThat(sa.examples().getFirst().labels()).isNotEqualTo(sb.examples().getFirst().labels());
        assertThat(sa.examples().getFirst().feature()).isEqualTo(sb.examples().getFirst().feature());
    }
    @Test void contextContainsTwentyPrecedingBarsOldestFirstAndExcludesAnchor() {
        var e = engine(DATE, START);
        for (int i = 0; i < 25; i++) bar(e, START + i * 300_000L, 100 + i);
        var s = e.finish(); var f = anchor(s, 20);
        assertThat(f.usable()).isTrue();
        for (int lag = 20; lag >= 1; lag--) {
            var expected = anchor(s, 20 - lag).contextValues();
            int offset = MlCandleDataset.featureColumns().indexOf("ctx_lag" + lag + "_return");
            assertThat(f.values().subList(offset, offset + expected.size())).isEqualTo(expected);
        }
        assertThat(f.values()).hasSize(MlCandleDataset.featureColumns().size());
        assertThat(s.warmupDiscarded()).isEqualTo(20);
        assertThat(s.horizonDiscarded()).isEqualTo(3);
        assertThat(s.examples()).hasSize(2);
    }
    @Test void firstTouchUsesLastSequenceEvenWhenBothLimitsShareTimestamp() {
        for (boolean upFirst : List.of(true, false)) {
            var e = engine(DATE, START);
            for (int i = 0; i <= 20; i++) bar(e, START + i * 300_000L, 100);
            long future = START + 21 * 300_000L;
            tick(e, future, upFirst ? 120 : 80); tick(e, future, upFirst ? 80 : 120); tick(e, future + 1, 100);
            bar(e, future + 300_000L, 100); bar(e, future + 600_000L, 100);
            var x = e.finish().examples().getFirst();
            assertThat(x.labels().get(1)).isEqualTo("20.0"); assertThat(x.labels().get(2)).isEqualTo("20.0");
            assertThat(x.labels().get(7)).isEqualTo("FLAT");
            assertThat(x.labels().get(8)).isEqualTo(upFirst ? "UP_FIRST" : "DOWN_FIRST");
        }
    }
    @Test void fiveFirstTouchPathsInsideSameM5AreDeterminedByLastOrder() {
        var paths = List.of(List.of(120.0,80.0), List.of(80.0,120.0), List.of(120.0,100.0),
                List.of(80.0,100.0), List.of(105.0,95.0));
        var expected = List.of("UP_FIRST", "DOWN_FIRST", "UP_FIRST", "DOWN_FIRST", "NONE");
        for (int n = 0; n < paths.size(); n++) {
            var e = engine(DATE, START);
            for (int i = 0; i <= 20; i++) bar(e, START + i * 300_000L, 100);
            long time = START + 21 * 300_000L;
            // Same M5, same timestamp, distinct original sequences; OHLC cannot distinguish first two paths.
            for (double price : paths.get(n)) tick(e, time, price);
            tick(e, time + 1, 100);
            bar(e, time + 300_000L, 100); bar(e, time + 600_000L, 100);
            assertThat(e.finish().examples().getFirst().labels().get(8)).isEqualTo(expected.get(n));
        }
    }
    @Test void directionUsesAnchorAtrAndFinalCloseWithInclusiveThresholds() {
        for (double close : List.of(110.0, 90.0, 100.0)) {
            var e = engine(DATE, START);
            for (int i = 0; i <= 22; i++) bar(e, START + i * 300_000L, 100);
            bar(e, START + 23 * 300_000L, close);
            var x = e.finish().examples().getFirst();
            assertThat(x.labels().get(7)).isEqualTo(close == 110 ? "UP" : close == 90 ? "DOWN" : "FLAT");
            assertThat(x.labels().get(8)).isEqualTo(close == 110 ? "UP_FIRST" : close == 90 ? "DOWN_FIRST" : "NONE");
        }
    }
    @Test void horizonIsThreeExactFutureBucketsAndExcludesTickAtHorizonEnd() {
        var e = engine(DATE, START);
        for (int i = 0; i < 24; i++) bar(e, START + i * 300_000L, 100);
        tick(e, START + 24 * 300_000L, 1000);
        var x = e.finish().examples().getFirst();
        assertThat(x.labels().get(0)).isEqualTo(Instant.ofEpochMilli(START + 24 * 300_000L).toString());
        assertThat(x.labels().get(1)).isEqualTo("5.0");
        assertThat(x.labels().get(8)).isEqualTo("NONE");
    }
    @Test void gapsAndSessionEndDoNotFabricateFutureOrCarryContext() {
        var e = engine(DATE, START);
        for (int i = 0; i < 22; i++) bar(e, START + i * 300_000L, 100);
        bar(e, START + 25 * 300_000L, 150);
        var s = e.finish();
        assertThat(s.examples()).isEmpty(); assertThat(s.horizonDiscarded()).isEqualTo(2);
        assertThat(s.candles().getLast().usable()).isFalse();
        var nextDate = DATE.plusDays(1); long nextStart = START + 86_400_000L;
        var next = engine(nextDate, nextStart); bar(next, nextStart, 1000);
        assertThat(next.finish().examples()).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> tick(e, nextStart, 1000));
        assertThat(s.candles().size()).isEqualTo(s.examples().size() + s.warmupDiscarded() + s.horizonDiscarded());
    }
    @Test void noSeedRequiresIndicatorAndContextWarmupAndEmptySessionHasNoRows() {
        var e = new MlCandleDataset("WIN$N", DATE, ZoneOffset.UTC, 1);
        for (int i = 0; i < 24; i++) bar(e, START + i * 300_000L, 100);
        var s = e.finish();
        assertThat(s.warmupDiscarded()).isGreaterThanOrEqualTo(20);
        assertThat(s.candles()).hasSize(24);
        assertThat(new MlCandleDataset("WIN$N", DATE, ZoneOffset.UTC, 1).finish().candles()).isEmpty();
    }
    @Test void eofPolicyExplainsOneAdditionalClosedCandlePerNonemptySession() {
        var legacy = new br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor();
        var ml = engine(DATE, START);
        int legacyClosed = 0;
        for (int i = 0; i < 2; i++) {
            var price = new CanonicalPriceEvent("WIN$N", START + i * 300_000L, 100);
            if (legacy.onEvent(price).completedCandle() != null) legacyClosed++;
            ml.onLast(new HistoricalCanonicalPriceEvent(price, 1, 8, sequence++));
        }
        assertThat(legacyClosed).isEqualTo(1);
        assertThat(ml.finish().candles()).hasSize(2);
    }
}
