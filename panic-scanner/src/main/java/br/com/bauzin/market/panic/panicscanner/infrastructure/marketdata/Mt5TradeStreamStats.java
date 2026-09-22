package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

public record Mt5TradeStreamStats(
        long tradesReceived,
        long buyTrades,
        long sellTrades,
        long ambiguousTrades,
        long parseErrors,
        long outOfOrderErrors,
        long lastTradeTimeMsc) {
}
