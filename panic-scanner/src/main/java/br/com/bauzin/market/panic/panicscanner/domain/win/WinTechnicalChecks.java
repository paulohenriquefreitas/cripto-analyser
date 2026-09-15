package br.com.bauzin.market.panic.panicscanner.domain.win;

public record WinTechnicalChecks(
        boolean trendAligned,
        boolean pullbackValid,
        boolean rejectionCandle,
        boolean executionTrigger,
        boolean overextended,
        boolean reversalCandidate,
        boolean reversalConfirmed,
        boolean breakout,
        boolean falseBreakout,
        boolean rewardRiskAccepted,
        boolean flowDataAvailable) {
}
