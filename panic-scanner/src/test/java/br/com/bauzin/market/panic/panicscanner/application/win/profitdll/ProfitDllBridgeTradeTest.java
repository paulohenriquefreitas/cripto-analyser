package br.com.bauzin.market.panic.panicscanner.application.win.profitdll;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinTrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitDllBridgeTradeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldDeserializeBridgeTradeAndConvertToWinTrade() throws Exception {
        String json = """
                {
                  "type": "trade",
                  "ticker": "WINFUT",
                  "exchange": "F",
                  "timestamp": "2026-09-11T22:47:24.462",
                  "tradeNumber": 398,
                  "price": 145150.0,
                  "quantity": 4,
                  "volume": 0.0,
                  "buyAgent": 0,
                  "sellAgent": 0,
                  "tradeType": 0
                }
                """;

        ProfitDllBridgeTrade bridgeTrade = objectMapper.readValue(json, ProfitDllBridgeTrade.class);
        WinTrade trade = bridgeTrade.toWinTrade();

        assertThat(bridgeTrade.isTrade()).isTrue();
        assertThat(bridgeTrade.matches("WINFUT", "F")).isTrue();
        assertThat(trade.symbol()).isEqualTo("WINFUT");
        assertThat(trade.timestamp().toString()).isEqualTo("2026-09-11T22:47:24.462");
        assertThat(trade.price()).isEqualTo(145150.0);
        assertThat(trade.quantity()).isEqualTo(4);
        assertThat(trade.financialVolume()).isEqualTo(580600.0);
    }
}
