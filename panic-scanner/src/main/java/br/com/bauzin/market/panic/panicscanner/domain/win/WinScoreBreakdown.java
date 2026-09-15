package br.com.bauzin.market.panic.panicscanner.domain.win;

public record WinScoreBreakdown(
        int trend,
        int buy,
        int sell,
        int execution,
        int sellerExhaustion,
        int buyerExhaustion) {
}
