package br.com.bauzin.market.panic.panicscanner.domain.checks;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;

import java.math.BigDecimal;

/** Structured technical-rule checks so clients never need to parse reason text. */
public record TechnicalChecks(
        boolean closeAboveSma21,
        boolean sma9AboveSma21,
        boolean rsiInsideRange,
        boolean adxAccepted,
        boolean liquidityAccepted,
        boolean historyAccepted) {

    public TechnicalChecks(boolean closeAboveSma21,
                           boolean sma9AboveSma21,
                           boolean rsiInsideRange,
                           boolean liquidityAccepted) {
        this(closeAboveSma21, sma9AboveSma21, rsiInsideRange, true, liquidityAccepted, true);
    }

    public boolean allAccepted() {
        return closeAboveSma21 && sma9AboveSma21 && rsiInsideRange && adxAccepted && liquidityAccepted && historyAccepted;
    }

    public static TechnicalChecks from(TechnicalAnalysis analysis,
                                       BigDecimal minRsi,
                                       BigDecimal maxRsi,
                                       BigDecimal minAdx,
                                       BigDecimal minAverageFinancialVolume20,
                                       boolean historyAccepted) {
        return new TechnicalChecks(
                analysis.lastClose().compareTo(analysis.sma21()) > 0,
                analysis.sma9().compareTo(analysis.sma21()) > 0,
                analysis.rsi9().compareTo(minRsi) >= 0 && analysis.rsi9().compareTo(maxRsi) <= 0,
                analysis.adx14().compareTo(minAdx) >= 0,
                analysis.averageFinancialVolume20().compareTo(minAverageFinancialVolume20) >= 0,
                historyAccepted);
    }

    public static TechnicalChecks from(TechnicalAnalysis analysis,
                                       BigDecimal minRsi,
                                       BigDecimal maxRsi,
                                       BigDecimal minAverageFinancialVolume20) {
        return from(analysis, minRsi, maxRsi, BigDecimal.ZERO, minAverageFinancialVolume20, true);
    }
}
