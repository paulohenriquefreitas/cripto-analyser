package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

public class WinBreakoutAnalyzer {

    public boolean breakoutUp(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return context.breakoutDistance().compareTo(context.atr14().multiply(config.breakout().minimumDistanceAtr())) >= 0
                && context.breakoutDistance().compareTo(context.atr14().multiply(config.breakout().maximumDistanceAtr())) <= 0
                && context.relativeVolume().compareTo(new java.math.BigDecimal("1.10")) >= 0
                && context.closeAboveEma21();
    }

    public boolean breakdownDown(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return context.breakoutDistance().compareTo(context.atr14().multiply(config.breakout().minimumDistanceAtr())) >= 0
                && context.breakoutDistance().compareTo(context.atr14().multiply(config.breakout().maximumDistanceAtr())) <= 0
                && context.relativeVolume().compareTo(new java.math.BigDecimal("1.10")) >= 0
                && context.closeBelowEma21();
    }

    public boolean falseBreakoutUp(WinTechnicalSnapshot context) {
        return context.upperWickPercent().compareTo(java.math.BigDecimal.valueOf(45)) >= 0
                && context.distanceFromSessionHigh().compareTo(context.atr14()) > 0
                && context.closeBelowEma21();
    }

    public boolean falseBreakdownDown(WinTechnicalSnapshot context) {
        return context.lowerWickPercent().compareTo(java.math.BigDecimal.valueOf(45)) >= 0
                && context.distanceFromSessionLow().compareTo(context.atr14()) > 0
                && context.closeAboveEma21();
    }
}
