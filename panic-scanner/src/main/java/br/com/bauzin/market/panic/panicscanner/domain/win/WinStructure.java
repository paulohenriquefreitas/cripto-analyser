package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;

public record WinStructure(
        boolean higherHigh,
        boolean higherLow,
        boolean lowerHigh,
        boolean lowerLow,
        boolean consolidation,
        BigDecimal sessionHigh,
        BigDecimal sessionLow,
        BigDecimal recentHigh,
        BigDecimal recentLow,
        BigDecimal microHigh,
        BigDecimal microLow) {
}
