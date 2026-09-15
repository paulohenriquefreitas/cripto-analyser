package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

public class WinConsolidationAnalyzer {

    public boolean consolidation(WinTechnicalSnapshot context, PanicScannerWinProperties config) {
        return context.consolidationWidth().compareTo(context.atr14().multiply(config.consolidation().maximumWidthAtr())) <= 0
                && context.movingAverageSlope9().abs().compareTo(config.consolidation().maximumSlopePercent()) <= 0
                && context.movingAverageSlope21().abs().compareTo(config.consolidation().maximumSlopePercent()) <= 0
                && !context.higherHighDetected()
                && !context.lowerLowDetected();
    }
}
