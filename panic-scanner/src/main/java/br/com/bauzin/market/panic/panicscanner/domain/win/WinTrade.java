package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.time.LocalDateTime;

public record WinTrade(
        String symbol,
        LocalDateTime timestamp,
        double price,
        long quantity,
        double financialVolume,
        Integer buyerAgent,
        Integer sellerAgent,
        Integer tradeType) {
}
