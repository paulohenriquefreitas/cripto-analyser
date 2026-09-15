package br.com.bauzin.market.panic.panicscanner.application.entry;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;

import java.util.List;

/** Application port for deterministic swing-trade entry analysis. */
public interface EntryAnalyzer {

    EntryAnalysis analyze(TechnicalAnalysis technicalAnalysis, List<Candle> closedCandles);
}
