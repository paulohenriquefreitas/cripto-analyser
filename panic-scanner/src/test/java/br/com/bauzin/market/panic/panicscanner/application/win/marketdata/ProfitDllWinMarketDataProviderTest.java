package br.com.bauzin.market.panic.panicscanner.application.win.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.profitdll.ProfitDllConfig;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitDllWinMarketDataProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldAppendBridgeTradesToExistingWinCandleFlow() {
        ProfitDllWinMarketDataProvider provider = new ProfitDllWinMarketDataProvider(
                new ProfitDllConfig(
                        URI.create("ws://127.0.0.1:8765"),
                        "WINFUT",
                        "F",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Duration.ofMillis(100),
                        true,
                        50),
                objectMapper);

        provider.handleBridgeMessage(trade("2026-09-11T10:04:59.000", 145150.0, 4));
        provider.handleBridgeMessage(trade("2026-09-11T10:05:00.000", 145155.0, 2));

        List<WinCandle> candles5m = provider.getIntradayCandles("WINFUT", "5m");
        WinMarketDataStatus status = provider.status();

        assertThat(candles5m).hasSize(52);
        assertThat(candles5m.get(49).endTime().toLocalTime().toString()).isEqualTo("10:00");
        assertThat(candles5m.get(50).startTime().toLocalTime().toString()).isEqualTo("10:00");
        assertThat(candles5m.get(51).startTime().toLocalTime().toString()).isEqualTo("10:05");
        assertThat(status.tradesReceived()).isEqualTo(2);
        assertThat(status.lastPrice()).isEqualByComparingTo("145155.0");
    }

    @Test
    void shouldWarmUpWithClosedCandlesBeforeFirstRealtimeTick() {
        ProfitDllWinMarketDataProvider provider = new ProfitDllWinMarketDataProvider(
                new ProfitDllConfig(
                        URI.create("ws://127.0.0.1:8765"),
                        "WINFUT",
                        "F",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Duration.ofMillis(100),
                        true,
                        50),
                objectMapper);

        provider.handleBridgeMessage(trade("2026-09-11T10:03:24.462", 145150.0, 4));

        List<WinCandle> candles5m = provider.getIntradayCandles("WINFUT", "5m");
        List<WinCandle> candles1m = provider.getIntradayCandles("WINFUT", "1m");

        assertThat(candles5m).hasSize(51);
        assertThat(candles1m).hasSize(251);
        assertThat(candles5m.get(49).endTime().toLocalTime().toString()).isEqualTo("10:00");
        assertThat(candles5m.get(49).endTime()).isBefore(candles5m.get(50).endTime());
        assertThat(candles1m.get(249).endTime().toLocalTime().toString()).isEqualTo("10:03");
        assertThat(candles1m.get(249).endTime()).isBefore(candles1m.get(250).endTime());
        assertThat(provider.status().message()).contains("WIN mock warm-up concluído");
    }

    @Test
    void shouldAllowDisablingMockWarmUpForRealProfitDll() {
        ProfitDllWinMarketDataProvider provider = new ProfitDllWinMarketDataProvider(
                new ProfitDllConfig(
                        URI.create("ws://127.0.0.1:8765"),
                        "WINFUT",
                        "F",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Duration.ofMillis(100),
                        false,
                        50),
                objectMapper);

        provider.handleBridgeMessage(trade("2026-09-11T10:03:24.462", 145150.0, 4));

        assertThat(provider.getIntradayCandles("WINFUT", "5m")).hasSize(1);
        assertThat(provider.getIntradayCandles("WINFUT", "1m")).hasSize(1);
        assertThat(provider.status().tradesReceived()).isEqualTo(1);
    }

    @Test
    void shouldIgnoreDifferentTickerOrExchange() {
        ProfitDllWinMarketDataProvider provider = new ProfitDllWinMarketDataProvider(
                new ProfitDllConfig(
                        URI.create("ws://127.0.0.1:8765"),
                        "WINFUT",
                        "F",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        Duration.ofMillis(100),
                        true,
                        50),
                objectMapper);

        provider.handleBridgeMessage("""
                {"type":"trade","ticker":"WDOFUT","exchange":"F","timestamp":"2026-09-11T10:00:00.000","price":5000.0,"quantity":1,"volume":0.0}
                """);

        assertThat(provider.status().tradesReceived()).isZero();
        assertThat(provider.getIntradayCandles("WINFUT", "5m")).isEmpty();
    }

    private String trade(String timestamp, double price, long quantity) {
        return """
                {"type":"trade","ticker":"WINFUT","exchange":"F","timestamp":"%s","tradeNumber":398,"price":%s,"quantity":%d,"volume":0.0,"buyAgent":0,"sellAgent":0,"tradeType":0}
                """.formatted(timestamp, price, quantity);
    }
}
