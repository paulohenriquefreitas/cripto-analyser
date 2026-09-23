package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalIntrabarShadowTest {
    private List<Mt5Candle> candles() {
        return IntStream.rangeClosed(1, 21).mapToObj(i -> new Mt5Candle(i * 300, 100, 110, 90, 100, 10, 100)).toList();
    }
    private Mt5Tick tick(long timeMsc, double price) { return new Mt5Tick(timeMsc / 1000, timeMsc, 99, 101, price); }

    @Test void sameBaselineComparesActualLegacyOhlcAndBothAverages() {
        var shadow = new CanonicalIntrabarShadow("WINV26", candles());
        for (int i = 0; i < 10; i++) assertTrue(shadow.onTick(tick(6_300_000 + i, 100 + i)).equivalent());
        assertEquals(10, shadow.samples());
        assertEquals(0, shadow.divergences());
        assertEquals(0, shadow.legacyUnavailable());
    }

    @Test void rolloverDivergenceRemainsVisibleAndOfficialResyncDoesNotRewriteCanonical() {
        var shadow = new CanonicalIntrabarShadow("WINV26", candles());
        var next = shadow.onTick(tick(6_600_010, 105));
        assertFalse(next.equivalent());
        assertNull(next.oldAverages());
        assertEquals(6_600_000, next.canonical().candle().bucketStartTimeMsc());
        var official = new java.util.ArrayList<>(candles());
        official.add(new Mt5Candle(6600, 101, 120, 95, 105, 10, 100));
        shadow.synchronizeLegacy(official);
        var after = shadow.onTick(tick(6_600_011, 105));
        assertFalse(after.equivalent());
        assertEquals(105, after.canonical().candle().open());
        assertEquals(101, after.oldCandle().open());
        assertEquals(after.oldAverages().value(), after.canonical().sma9());
        assertEquals(2, shadow.divergences());
        assertEquals(1, shadow.legacyUnavailable());
    }
}
