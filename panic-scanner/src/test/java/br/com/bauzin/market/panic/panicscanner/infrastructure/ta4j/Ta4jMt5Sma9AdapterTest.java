package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ta4jMt5Sma9AdapterTest {
    private static final long START = 1789991700L;
    private List<Mt5Candle> candles(int count) {
        return IntStream.range(0, count).mapToObj(i ->
                new Mt5Candle(START + i * 300L, 100 + i, 120 + i, 90 + i, 100 + i, 12, 345)).toList();
    }
    private Mt5Tick tick(long time, double last) {
        return new Mt5Tick(time, time * 1000 + 123, last - 5, last + 5, last, 9);
    }

    @Test void converterPreservesOhlcRawTimeAndRealVolume() {
        var bar = Mt5BarSeriesConverter.convert(candles(1)).getFirstBar();
        assertEquals(Instant.ofEpochSecond(START), bar.getBeginTime());
        assertEquals(Instant.ofEpochSecond(START + 300), bar.getEndTime());
        assertEquals(100, bar.getOpenPrice().doubleValue());
        assertEquals(120, bar.getHighPrice().doubleValue());
        assertEquals(90, bar.getLowPrice().doubleValue());
        assertEquals(100, bar.getClosePrice().doubleValue());
        assertEquals(345, bar.getVolume().doubleValue());
    }

    @Test void fullWindowsOnlyAndKnownMovingAverage() {
        var result = new Ta4jMt5Sma9Adapter().synchronize(candles(10));
        for (int i = 0; i < 8; i++) assertNull(result.get(i).sma9());
        assertEquals(104, result.get(8).sma9());
        assertEquals(105, result.get(9).sma9());
        assertEquals(candles(10).getLast(), result.getLast().candle());
    }

    @Test void repeatedIntrabarUpdatesInvalidateCacheAndPreserveHistory() {
        var adapter = new Ta4jMt5Sma9Adapter();
        var historical = adapter.synchronize(candles(9));
        long time = candles(9).getLast().time();
        assertEquals(105, adapter.onTick(tick(time + 1, 117)).value());
        assertEquals(104.33333333333333, adapter.onTick(tick(time + 2, 111)).value(), 1e-9);
        assertEquals(104, adapter.onTick(tick(time + 3, 108)).value());
        assertEquals(104, historical.getLast().sma9());
        assertEquals(108, historical.getLast().candle().close());
        assertNull(adapter.onTick(tick(time + 1, 999)));
    }

    @Test void newBucketWaitsForOfficialRestAndReplaysBufferedTick() {
        var adapter = new Ta4jMt5Sma9Adapter();
        adapter.synchronize(candles(9));
        long next = candles(10).getLast().time();
        var latest = tick(next + 1, 118);
        assertNull(adapter.onTick(latest));
        var official = adapter.synchronize(candles(10));
        assertEquals(105, official.getLast().sma9());
        var live = adapter.onTick(latest);
        assertEquals(next, live.time());
        assertEquals(106, live.value());
        assertEquals(104, official.get(8).sma9());
    }

    @Test void tickBeforeHistoryAndInsufficientOrEmptyHistory() {
        var adapter = new Ta4jMt5Sma9Adapter();
        var latest = tick(START + 8 * 300 + 1, 117);
        assertNull(adapter.onTick(latest));
        assertTrue(adapter.synchronize(List.of()).isEmpty());
        adapter.synchronize(candles(9));
        assertEquals(105, adapter.onTick(latest).value());
        var shortHistory = new Ta4jMt5Sma9Adapter();
        shortHistory.synchronize(candles(8));
        assertNull(shortHistory.onTick(tick(START + 7 * 300 + 1, 117)));
    }

    @Test void boundedHistoryAndInvalidTicks() {
        var adapter = new Ta4jMt5Sma9Adapter();
        var result = adapter.synchronize(candles(1100));
        assertEquals(1000, result.size());
        assertEquals(1195, result.getLast().sma9());
        long time = result.getLast().candle().time();
        assertNull(adapter.onTick(tick(time, Double.NaN)));
        assertNull(adapter.onTick(tick(time, 0)));
        assertNull(adapter.onTick(new Mt5Tick(time, 1, 100, 101, 100, 1)));
    }
    @Test void sma21SharesCloseSeriesAndRequiresFullWindow() {
        var adapter = new Ta4jMt5Sma9Adapter();
        var input = IntStream.rangeClosed(1, 22).mapToObj(i ->
                new Mt5Candle(START + (i - 1) * 300L, i, i + 30, i, i, 12, 345)).toList();
        var result = adapter.synchronize(input);
        for (int i = 0; i < 20; i++) assertNull(result.get(i).sma21());
        assertEquals(11, result.get(20).sma21());
        assertEquals(12, result.get(21).sma21());
        assertEquals(17, result.get(20).sma9());
        assertEquals(18, result.get(21).sma9());
        long time = input.getLast().time();
        var changed = adapter.onTick(tick(time + 1, 43));
        assertEquals(13, changed.sma21()); // last close 22 -> 43
        assertEquals(18 + 21.0 / 9, changed.value(), 1e-9);
        var restored = adapter.onTick(tick(time + 2, 22));
        assertEquals(12, restored.sma21());
        assertEquals(18, restored.value());
        assertEquals(11, result.get(20).sma21());
        assertEquals(12, result.get(21).sma21());
        assertNull(adapter.onTick(tick(time + 300, 44)));
    }

    @Test void sma21RolloverReplaysLatestTickAfterOfficialHistory() {
        var adapter = new Ta4jMt5Sma9Adapter();
        adapter.synchronize(candles(21));
        long next = candles(22).getLast().time();
        var latest = tick(next + 1, 142);
        assertNull(adapter.onTick(latest));
        var official = adapter.synchronize(candles(22));
        assertEquals(111, official.getLast().sma21());
        assertEquals(112, adapter.onTick(latest).sma21());
        assertEquals(next, adapter.onTick(latest).time());
    }
}
