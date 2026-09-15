package br.com.bauzin.market.panic.panicscanner.domain.strategy;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;

/** Strategy contract for converting technical analysis into a scanner decision. */
public interface ScannerStrategy {

    ScannerResult execute(TechnicalAnalysis analysis);
}
