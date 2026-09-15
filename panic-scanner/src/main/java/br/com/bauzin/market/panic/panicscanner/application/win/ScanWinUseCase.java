package br.com.bauzin.market.panic.panicscanner.application.win;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinAnalysis;

import org.springframework.stereotype.Service;

@Service
public class ScanWinUseCase {

    private final AnalyzeWinUseCase analyzeWinUseCase;

    public ScanWinUseCase(AnalyzeWinUseCase analyzeWinUseCase) {
        this.analyzeWinUseCase = analyzeWinUseCase;
    }

    public WinAnalysis execute(String contract, String contextTimeframe, String executionTimeframe) {
        return analyzeWinUseCase.execute(contract, contextTimeframe, executionTimeframe);
    }
}
