package br.com.bauzin.market.panic.panicscanner.domain.win.analysis;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinStructure;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

public class WinStructureAnalyzer {

    public WinStructure analyze(WinTechnicalSnapshot context, WinTechnicalSnapshot execution, boolean consolidation) {
        return new WinStructure(
                context.higherHighDetected(),
                context.higherLowDetected(),
                context.lowerHighDetected(),
                context.lowerLowDetected(),
                consolidation,
                context.sessionHigh(),
                context.sessionLow(),
                context.recentHigh(),
                context.recentLow(),
                execution.recentHigh(),
                execution.recentLow());
    }
}
