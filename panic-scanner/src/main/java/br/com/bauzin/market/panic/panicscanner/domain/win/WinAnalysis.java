package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record WinAnalysis(
        String contract,
        OffsetDateTime analyzedAt,
        String contextTimeframe,
        String executionTimeframe,
        BigDecimal currentPrice,
        WinMarketState marketState,
        WinSignal signal,
        WinExecutionStatus executionStatus,
        WinSetupType setupType,
        TradeLocation tradeLocation,
        WinScoreBreakdown scores,
        WinTechnicalSnapshot technical5m,
        WinTechnicalSnapshot technical1m,
        WinStructure structure,
        WinFlowSnapshot flow,
        WinTradePlan tradePlan,
        WinTechnicalChecks checks,
        List<String> reasons) {
}
