package br.com.bauzin.market.panic.panicscanner.domain.strategy;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.MomentumScoringConfig;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.ScoreCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Current momentum scanner strategy preserving the first-iteration business rules. */
public class MomentumScannerStrategy implements ScannerStrategy {

    private final MomentumScoringConfig scoringConfig;
    private final ScoreCalculator scoreCalculator;

    public MomentumScannerStrategy(BigDecimal minAverageFinancialVolume20, ScoreCalculator scoreCalculator) {
        this(new MomentumScoringConfig(
                minAverageFinancialVolume20,
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(70),
                BigDecimal.ZERO,
                70,
                BigDecimal.valueOf(8),
                new BigDecimal("1.2")),
                scoreCalculator);
    }

    public MomentumScannerStrategy(MomentumScoringConfig scoringConfig, ScoreCalculator scoreCalculator) {
        this.scoringConfig = scoringConfig;
        this.scoreCalculator = scoreCalculator;
    }

    @Override
    public ScannerResult execute(TechnicalAnalysis analysis) {
        TechnicalChecks checks = TechnicalChecks.from(
                analysis,
                scoringConfig.minimumRsi(),
                scoringConfig.maximumRsi(),
                scoringConfig.minimumAdx(),
                scoringConfig.minimumAverageFinancialVolume(),
                true);
        int score = scoreCalculator.calculate(checks, analysis, scoringConfig);
        List<String> reasons = reasons(checks);

        SignalStatus status;
        if (checks.allAccepted() && score >= scoringConfig.minimumQualifiedScore()) {
            status = SignalStatus.QUALIFIED;
            reasons = List.of("Momentum qualified: mandatory momentum checks passed");
        } else if (!checks.liquidityAccepted()) {
            status = SignalStatus.REJECTED;
        } else {
            status = SignalStatus.WATCH;
        }

        return new ScannerResult(
                analysis.ticker(),
                analysis.analysisDate(),
                analysis.timeframe(),
                analysis.lastClose(),
                analysis.lastClose(),
                br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.CLOSED,
                null,
                null,
                score,
                status,
                analysis.trend(),
                analysis,
                null,
                checks,
                reasons);
    }

    private List<String> reasons(TechnicalChecks checks) {
        List<String> reasons = new ArrayList<>();
        if (!checks.closeAboveSma21()) {
            reasons.add("Last close is not above SMA21");
        }
        if (!checks.sma9AboveSma21()) {
            reasons.add("SMA9 is not above SMA21");
        }
        if (!checks.rsiInsideRange()) {
            reasons.add("RSI9 is outside the 50-70 momentum range");
        }
        if (!checks.adxAccepted()) {
            reasons.add("ADX14 is below threshold " + scoringConfig.minimumAdx());
        }
        if (!checks.liquidityAccepted()) {
            reasons.add("Average daily financial volume over 20 candles is below threshold "
                    + scoringConfig.minimumAverageFinancialVolume());
        }
        if (!checks.historyAccepted()) {
            reasons.add("Insufficient closed candle history");
        }
        return reasons;
    }
}
