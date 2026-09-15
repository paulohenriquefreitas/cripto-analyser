package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;

public record WinTradePlan(
        BigDecimal suggestedEntry,
        BigDecimal technicalStop,
        BigDecimal riskPoints,
        BigDecimal target1,
        BigDecimal target2,
        BigDecimal rewardRiskTarget1,
        BigDecimal rewardRiskTarget2) {

    public static WinTradePlan empty() {
        return new WinTradePlan(null, null, null, null, null, null, null);
    }

    public boolean valid() {
        return suggestedEntry != null && technicalStop != null && riskPoints != null && riskPoints.signum() > 0;
    }
}
