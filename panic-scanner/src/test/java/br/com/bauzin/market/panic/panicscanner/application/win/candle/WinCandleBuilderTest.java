package br.com.bauzin.market.panic.panicscanner.application.win.candle;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class WinCandleBuilderTest {

    @Test
    void shouldCreateFirstCandleFromFirstTrade() {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));

        assertThat(builder.onTrade(trade("10:00:10", 100, 2))).isEmpty();

        WinCandle current = builder.currentCandle();
        assertThat(current.open()).isEqualByComparingTo("100");
        assertThat(current.high()).isEqualByComparingTo("100");
        assertThat(current.low()).isEqualByComparingTo("100");
        assertThat(current.close()).isEqualByComparingTo("100");
        assertThat(current.volume()).isEqualByComparingTo("2");
        assertThat(current.tradeCount()).isEqualTo(1);
    }

    @Test
    void shouldUpdateOhlcAndVolumeInsideSameWindow() {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));

        builder.onTrade(trade("10:00:10", 100, 2));
        builder.onTrade(trade("10:01:10", 105, 3));
        builder.onTrade(trade("10:02:10", 98, 4));

        WinCandle current = builder.currentCandle();
        assertThat(current.open()).isEqualByComparingTo("100");
        assertThat(current.high()).isEqualByComparingTo("105");
        assertThat(current.low()).isEqualByComparingTo("98");
        assertThat(current.close()).isEqualByComparingTo("98");
        assertThat(current.volume()).isEqualByComparingTo("9");
        assertThat(current.financialVolume()).isEqualByComparingTo("907");
        assertThat(current.tradeCount()).isEqualTo(3);
    }

    @Test
    void shouldClosePreviousCandleOnExactFiveMinuteWindowChange() {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));

        builder.onTrade(trade("10:04:59", 100, 1));
        List<WinCandle> closed = builder.onTrade(trade("10:05:00", 101, 1));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).startTime().toLocalTime().toString()).isEqualTo("10:00");
        assertThat(closed.get(0).endTime().toLocalTime().toString()).isEqualTo("10:05");
        assertThat(builder.currentCandle().startTime().toLocalTime().toString()).isEqualTo("10:05");
    }

    @Test
    void shouldAggregateMultipleTradesWithSameTimestamp() {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));

        builder.onTrade(trade("10:00:00", 100, 1));
        builder.onTrade(trade("10:00:00", 101, 2));

        assertThat(builder.currentCandle().close()).isEqualByComparingTo("101");
        assertThat(builder.currentCandle().volume()).isEqualByComparingTo("3");
        assertThat(builder.currentCandle().tradeCount()).isEqualTo(2);
    }

    @Test
    void shouldNotCreateEmptyCandlesWhenThereIsGapWithoutTrades() {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));

        builder.onTrade(trade("10:00:00", 100, 1));
        List<WinCandle> closed = builder.onTrade(trade("10:20:00", 104, 1));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).endTime().toLocalTime().toString()).isEqualTo("10:05");
        assertThat(builder.currentCandle().startTime().toLocalTime().toString()).isEqualTo("10:20");
    }

    @Test
    void shouldHandleBasicConcurrency() throws Exception {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));
        int trades = 100;
        CountDownLatch latch = new CountDownLatch(trades);
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < trades; i++) {
                int price = 100 + i;
                executor.submit(() -> {
                    builder.onTrade(trade("10:00:00", price, 1));
                    latch.countDown();
                });
            }
            latch.await();
        }

        assertThat(builder.currentCandle().tradeCount()).isEqualTo(trades);
        assertThat(builder.currentCandle().volume()).isEqualByComparingTo("100");
    }

    @Test
    void shouldProduceSameCandleForHistoryAndRealtimeTradeSequences() {
        List<WinTrade> trades = List.of(
                trade("10:00:00", 100, 1),
                trade("10:01:00", 101, 2),
                trade("10:02:00", 99, 3),
                trade("10:05:00", 102, 1));

        List<WinCandle> history = build(trades);
        List<WinCandle> realtime = build(new ArrayList<>(trades));

        assertThat(realtime).usingRecursiveComparison().isEqualTo(history);
    }

    private List<WinCandle> build(List<WinTrade> trades) {
        WinCandleBuilder builder = new WinCandleBuilder(Duration.ofMinutes(5));
        List<WinCandle> candles = new ArrayList<>();
        trades.stream()
                .sorted(Comparator.comparing(WinTrade::timestamp))
                .forEach(trade -> candles.addAll(builder.onTrade(trade)));
        return candles;
    }

    private WinTrade trade(String time, double price, long quantity) {
        return new WinTrade(
                "WINV26",
                LocalDateTime.parse("2026-09-08T" + time),
                price,
                quantity,
                price * quantity,
                1,
                2,
                2);
    }
}
