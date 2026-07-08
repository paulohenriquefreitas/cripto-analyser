package br.com.bauzin.crypto.analyzer;

import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.SequenceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceAnalyzerTest {

    private final SequenceAnalyzer sequenceAnalyzer = new SequenceAnalyzer();

    @Test
    void shouldGroupSequenceDistributionByDirectionAndLength() {
        List<Candle> candles = new ArrayList<>();
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");
        long minuteMillis = 60_000L;
        int index = 0;

        index = appendBullish(candles, baseTime, minuteMillis, index, 1);
        index = appendBearish(candles, baseTime, minuteMillis, index, 1);
        index = appendBullish(candles, baseTime, minuteMillis, index, 2);
        index = appendBearish(candles, baseTime, minuteMillis, index, 2);
        index = appendBullish(candles, baseTime, minuteMillis, index, 3);
        index = appendBearish(candles, baseTime, minuteMillis, index, 3);
        index = appendBullish(candles, baseTime, minuteMillis, index, 10);
        index = appendBearish(candles, baseTime, minuteMillis, index, 10);
        index = appendBullish(candles, baseTime, minuteMillis, index, 11);
        index = appendBearish(candles, baseTime, minuteMillis, index, 11);
        appendBullish(candles, baseTime, minuteMillis, index, 14);

        SequenceResult result = sequenceAnalyzer.analyze(candles);

        assertThat(result.getLargestBullishSequence()).isEqualTo(14);
        assertThat(result.getLargestBearishSequence()).isEqualTo(11);
        assertThat(result.getBullishSequenceDistribution())
                .isEqualTo(Map.of(1, 1, 2, 1, 3, 1, 10, 1, 11, 1, 14, 1));
        assertThat(result.getBearishSequenceDistribution())
                .isEqualTo(Map.of(1, 1, 2, 1, 3, 1, 10, 1, 11, 1));
    }

    private int appendBullish(List<Candle> candles,
                              Instant baseTime,
                              long minuteMillis,
                              int startIndex,
                              int length) {
        return appendCandles(candles, baseTime, minuteMillis, startIndex, length, true);
    }

    private int appendBearish(List<Candle> candles,
                              Instant baseTime,
                              long minuteMillis,
                              int startIndex,
                              int length) {
        return appendCandles(candles, baseTime, minuteMillis, startIndex, length, false);
    }

    private int appendCandles(List<Candle> candles,
                              Instant baseTime,
                              long minuteMillis,
                              int startIndex,
                              int length,
                              boolean bullish) {
        for (int i = 0; i < length; i++) {
            int currentIndex = startIndex + i;
            Instant openTime = baseTime.plusMillis(currentIndex * minuteMillis);

            candles.add(Candle.builder()
                    .openTime(openTime)
                    .closeTime(openTime.plusMillis(minuteMillis - 1))
                    .open(BigDecimal.ONE)
                    .high(BigDecimal.valueOf(2))
                    .low(BigDecimal.ONE)
                    .close(bullish ? BigDecimal.valueOf(2) : BigDecimal.ZERO)
                    .volume(BigDecimal.ONE)
                    .quoteAssetVolume(BigDecimal.ONE)
                    .trades(1L)
                    .takerBuyBaseVolume(BigDecimal.ONE)
                    .takerBuyQuoteVolume(BigDecimal.ONE)
                    .build());
        }

        return startIndex + length;
    }
}
