package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record WinFlowIngestionRequest(
        @NotBlank String contract,
        BigDecimal buyAggression,
        BigDecimal sellAggression,
        BigDecimal delta,
        BigDecimal accumulated,
        BigDecimal aggressionSlope,
        BigDecimal aggressionEfficiency,
        Boolean possibleBuyerAbsorption,
        Boolean possibleSellerAbsorption) {

    public WinFlowSnapshot toFlow() {
        return new WinFlowSnapshot(
                true,
                buyAggression,
                sellAggression,
                delta,
                accumulated,
                aggressionSlope,
                aggressionEfficiency,
                possibleBuyerAbsorption,
                possibleSellerAbsorption);
    }
}
