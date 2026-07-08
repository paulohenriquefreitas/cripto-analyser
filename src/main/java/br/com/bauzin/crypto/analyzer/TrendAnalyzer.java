package br.com.bauzin.crypto.analyzer;

import br.com.bauzin.crypto.enums.TrendDirection;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.SequenceResult;
import br.com.bauzin.crypto.model.TrendAnalysisResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

@Service
public class TrendAnalyzer {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIDEWAYS_THRESHOLD = BigDecimal.valueOf(0.30);
    private static final BigDecimal TREND_THRESHOLD = BigDecimal.ONE;
    private static final BigDecimal STRONG_THRESHOLD = BigDecimal.valueOf(2);
    private static final BigDecimal MODERATE_RATIO_THRESHOLD = BigDecimal.valueOf(55);
    private static final BigDecimal STRONG_RATIO_THRESHOLD = BigDecimal.valueOf(65);

    private final SequenceAnalyzer sequenceAnalyzer;

    public TrendAnalyzer(SequenceAnalyzer sequenceAnalyzer) {
        this.sequenceAnalyzer = sequenceAnalyzer;
    }

    public TrendAnalysisResult analyze(List<Candle> candles) {
        List<Candle> safeCandles = candles == null ? Collections.emptyList() : candles;

        if (safeCandles.isEmpty()) {
            return TrendAnalysisResult.builder()
                    .direction(TrendDirection.SIDEWAYS)
                    .strength("NONE")
                    .summary("No candles available for analysis.")
                    .analyzedCandles(0)
                    .priceChange(BigDecimal.ZERO)
                    .priceChangePercent(BigDecimal.ZERO)
                    .bullishCandles(0)
                    .bearishCandles(0)
                    .dojiCandles(0)
                    .largestBullishSequence(0)
                    .largestBearishSequence(0)
                    .bullishRatioPercent(BigDecimal.ZERO)
                    .bearishRatioPercent(BigDecimal.ZERO)
                    .build();
        }

        SequenceResult sequenceResult = sequenceAnalyzer.analyze(safeCandles);
        Candle first = safeCandles.getFirst();
        Candle last = safeCandles.getLast();

        BigDecimal startPrice = first.getOpen();
        BigDecimal endPrice = last.getClose();
        BigDecimal priceChange = endPrice.subtract(startPrice);
        BigDecimal priceChangePercent = calculatePercentChange(startPrice, endPrice);
        BigDecimal bullishRatio = calculateRatio(sequenceResult.getBullishCandles(), sequenceResult.getTotalCandles());
        BigDecimal bearishRatio = calculateRatio(sequenceResult.getBearishCandles(), sequenceResult.getTotalCandles());

        TrendDirection direction = resolveDirection(
                priceChangePercent,
                sequenceResult.getBullishCandles(),
                sequenceResult.getBearishCandles(),
                sequenceResult.getLargestBullishSequence(),
                sequenceResult.getLargestBearishSequence(),
                bullishRatio,
                bearishRatio);

        String strength = resolveStrength(direction, priceChangePercent, bullishRatio, bearishRatio);

        return TrendAnalysisResult.builder()
                .direction(direction)
                .strength(strength)
                .summary(buildSummary(direction, strength, priceChangePercent, bullishRatio, bearishRatio))
                .analyzedCandles(sequenceResult.getTotalCandles())
                .startTime(first.getOpenTime())
                .endTime(last.getCloseTime())
                .startPrice(startPrice)
                .endPrice(endPrice)
                .priceChange(priceChange)
                .priceChangePercent(priceChangePercent)
                .bullishCandles(sequenceResult.getBullishCandles())
                .bearishCandles(sequenceResult.getBearishCandles())
                .dojiCandles(sequenceResult.getDojiCandles())
                .largestBullishSequence(sequenceResult.getLargestBullishSequence())
                .largestBearishSequence(sequenceResult.getLargestBearishSequence())
                .bullishRatioPercent(bullishRatio)
                .bearishRatioPercent(bearishRatio)
                .build();
    }

    private TrendDirection resolveDirection(BigDecimal priceChangePercent,
                                            Integer bullishCandles,
                                            Integer bearishCandles,
                                            Integer largestBullishSequence,
                                            Integer largestBearishSequence,
                                            BigDecimal bullishRatio,
                                            BigDecimal bearishRatio) {

        BigDecimal absoluteChange = priceChangePercent.abs();

        if (absoluteChange.compareTo(SIDEWAYS_THRESHOLD) < 0) {
            return TrendDirection.SIDEWAYS;
        }

        if (priceChangePercent.compareTo(TREND_THRESHOLD) >= 0
                && bullishCandles >= bearishCandles) {
            return TrendDirection.UPTREND;
        }

        if (priceChangePercent.compareTo(TREND_THRESHOLD.negate()) <= 0
                && bearishCandles >= bullishCandles) {
            return TrendDirection.DOWNTREND;
        }

        if (bullishRatio.compareTo(MODERATE_RATIO_THRESHOLD) >= 0
                && largestBullishSequence >= largestBearishSequence) {
            return TrendDirection.UPTREND;
        }

        if (bearishRatio.compareTo(MODERATE_RATIO_THRESHOLD) >= 0
                && largestBearishSequence >= largestBullishSequence) {
            return TrendDirection.DOWNTREND;
        }

        return TrendDirection.SIDEWAYS;
    }

    private String resolveStrength(TrendDirection direction,
                                   BigDecimal priceChangePercent,
                                   BigDecimal bullishRatio,
                                   BigDecimal bearishRatio) {

        if (direction == TrendDirection.SIDEWAYS) {
            return "WEAK";
        }

        BigDecimal dominantRatio = direction == TrendDirection.UPTREND ? bullishRatio : bearishRatio;
        BigDecimal absoluteChange = priceChangePercent.abs();

        if (absoluteChange.compareTo(STRONG_THRESHOLD) >= 0
                && dominantRatio.compareTo(STRONG_RATIO_THRESHOLD) >= 0) {
            return "STRONG";
        }

        if (absoluteChange.compareTo(TREND_THRESHOLD) >= 0
                && dominantRatio.compareTo(MODERATE_RATIO_THRESHOLD) >= 0) {
            return "MODERATE";
        }

        return "WEAK";
    }

    private String buildSummary(TrendDirection direction,
                                String strength,
                                BigDecimal priceChangePercent,
                                BigDecimal bullishRatio,
                                BigDecimal bearishRatio) {

        BigDecimal dominantRatio = direction == TrendDirection.DOWNTREND ? bearishRatio : bullishRatio;
        return "%s %s with %s%% price variation and %s%% dominant candle ratio."
                .formatted(direction, strength, priceChangePercent, dominantRatio);
    }

    private BigDecimal calculatePercentChange(BigDecimal startPrice, BigDecimal endPrice) {
        if (startPrice == null || endPrice == null || startPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return endPrice.subtract(startPrice)
                .divide(startPrice, 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateRatio(Integer partial, Integer total) {
        if (partial == null || total == null || total == 0) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(partial)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
