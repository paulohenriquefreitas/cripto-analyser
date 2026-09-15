package br.com.bauzin.market.panic.panicscanner.application.win.marketdata;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WinMarketDataStatus(
        String provider,
        boolean dllLoaded,
        boolean connected,
        boolean subscribed,
        String symbol,
        OffsetDateTime lastTradeTime,
        BigDecimal lastPrice,
        long tradesReceived,
        OffsetDateTime lastClosedCandle,
        String message) {
}
