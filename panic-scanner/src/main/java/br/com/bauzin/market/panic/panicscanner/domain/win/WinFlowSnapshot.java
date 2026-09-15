package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;

public record WinFlowSnapshot(
        boolean available,
        BigDecimal buyAggression,
        BigDecimal sellAggression,
        BigDecimal delta,
        BigDecimal accumulated,
        BigDecimal aggressionSlope,
        BigDecimal aggressionEfficiency,
        Boolean possibleBuyerAbsorption,
        Boolean possibleSellerAbsorption) {

    public static WinFlowSnapshot unavailable() {
        return new WinFlowSnapshot(false, null, null, null, null, null, null, null, null);
    }
}
