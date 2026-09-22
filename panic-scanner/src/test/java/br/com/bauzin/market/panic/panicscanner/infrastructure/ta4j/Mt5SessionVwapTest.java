package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mt5SessionVwapTest {
    private static final long DAY = Instant.parse("2026-09-21T00:00:00Z").getEpochSecond();
    private Mt5Candle candle(long time, double high, double low, double close, long volume) {
        return new Mt5Candle(time, low, high, low, close, 99999, volume);
    }
    private Mt5Candle previous() { return candle(DAY - 300, 999, 999, 999, 999); }
    private Mt5SessionVwap vwap(List<Mt5Candle> candles) {
        return new Mt5SessionVwap(Mt5BarSeriesConverter.convert(candles));
    }

    @Test void typicalPriceAndRealVolumeAccumulateThenResetAcrossTwoSessions() {
        var rows = List.of(previous(),
                candle(DAY + 32400, 12, 6, 12, 2), // HLC3=10
                candle(DAY + 32700, 24, 12, 24, 3), // HLC3=20
                candle(DAY + 33000, 36, 18, 36, 5), // HLC3=30
                candle(DAY + 86400 + 32400, 100, 100, 100, 1),
                candle(DAY + 86400 + 32700, 200, 200, 200, 3));
        var indicator = vwap(rows);
        assertNull(indicator.value(0)); // unknown start of first observed date
        assertEquals(10, indicator.value(1));
        assertEquals(16, indicator.value(2)); // (10*2+20*3)/5
        assertEquals(23, indicator.value(3)); // (20+60+150)/10
        assertEquals(100, indicator.value(4));
        assertEquals(175, indicator.value(5));
        var dto = new Ta4jMt5Sma9Adapter().synchronize(rows);
        assertEquals(23, dto.get(3).vwap());
        assertEquals("2026-09-21", dto.get(3).vwapSession());
        assertEquals("2026-09-22", dto.get(4).vwapSession());
        assertEquals(rows.get(4), dto.get(4).candle());
    }

    @Test void zeroVolumeDoesNotInventWeightAndNegativeVolumeInvalidatesUntilReset() {
        var indicator = vwap(List.of(previous(),
                candle(DAY, 10, 10, 10, 0),
                candle(DAY + 300, 20, 20, 20, 2),
                candle(DAY + 600, 999, 999, 999, 0),
                candle(DAY + 900, 30, 30, 30, -1),
                candle(DAY + 1200, 40, 40, 40, 2),
                candle(DAY + 86400, 50, 50, 50, 1)));
        assertNull(indicator.value(1));
        assertEquals(20, indicator.value(2));
        assertEquals(20, indicator.value(3));
        assertNull(indicator.value(4));
        assertNull(indicator.value(5));
        assertEquals(50, indicator.value(6));
    }

    @Test void incompleteFirstSessionIsNotPresentedAsDailyVwap() {
        var indicator = vwap(List.of(candle(DAY + 40000, 10, 10, 10, 2),
                candle(DAY + 40300, 20, 20, 20, 3)));
        assertNull(indicator.value(0));
        assertNull(indicator.value(1));
    }

    @Test void sessionUsesBarOpenAndIsIndependentOfJvmTimezone() {
        var original = TimeZone.getDefault();
        try {
            for (String zone : List.of("Asia/Tokyo", "America/Sao_Paulo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var indicator = vwap(List.of(previous(), candle(DAY, 10, 10, 10, 1)));
                assertEquals("2026-09-20", indicator.session(0).toString());
                assertEquals("2026-09-21", indicator.session(1).toString());
                assertEquals(10, indicator.value(1));
            }
        } finally { TimeZone.setDefault(original); }
    }

    @Test void calculationIncludesSessionBarsBeforeVisibleHundredAndTickCannotChangeSnapshot() {
        var rows = new ArrayList<Mt5Candle>(); rows.add(previous());
        for (int i = 0; i < 120; i++) rows.add(candle(DAY + i * 300, i + 1, i + 1, i + 1, 1));
        var adapter = new Ta4jMt5Sma9Adapter();
        var result = adapter.synchronize(rows);
        assertEquals(60.5, result.getLast().vwap()); // all 1..120, not 21..120
        long time = rows.getLast().time();
        adapter.onTick(new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick(
                time + 1, (time + 1) * 1000, 998, 1000, 999, 99999));
        assertEquals(60.5, result.getLast().vwap());
    }
}
