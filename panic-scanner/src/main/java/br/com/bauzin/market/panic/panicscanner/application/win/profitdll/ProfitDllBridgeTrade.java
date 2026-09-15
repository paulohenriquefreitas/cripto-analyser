package br.com.bauzin.market.panic.panicscanner.application.win.profitdll;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinTrade;

import java.time.LocalDateTime;

public record ProfitDllBridgeTrade(
        String type,
        String ticker,
        String exchange,
        LocalDateTime timestamp,
        Integer tradeNumber,
        Double price,
        Long quantity,
        Double volume,
        Integer buyAgent,
        Integer sellAgent,
        Integer tradeType) {

    public boolean isTrade() {
        return "trade".equalsIgnoreCase(type);
    }

    public boolean matches(String expectedTicker, String expectedExchange) {
        return equalsIgnoreCase(ticker, expectedTicker) && equalsIgnoreCase(exchange, expectedExchange);
    }

    public WinTrade toWinTrade() {
        if (ticker == null || timestamp == null || price == null || quantity == null) {
            throw new IllegalArgumentException("ProfitDLL bridge trade incompleto");
        }
        double financialVolume = volume != null && volume > 0 ? volume : price * quantity;
        return new WinTrade(
                ticker,
                timestamp,
                price,
                quantity,
                financialVolume,
                buyAgent,
                sellAgent,
                tradeType);
    }

    private boolean equalsIgnoreCase(String actual, String expected) {
        if (expected == null || expected.isBlank()) return true;
        return actual != null && actual.equalsIgnoreCase(expected);
    }
}
