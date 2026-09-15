package br.com.bauzin.market.panic.panicscanner.application.win;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;

import java.util.List;

public interface WinTechnicalAnalysisEngine {

    int minimumRequiredCandles();

    WinTechnicalSnapshot analyze(String timeframe, List<WinCandle> candles);
}
