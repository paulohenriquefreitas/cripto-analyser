package br.com.bauzin.market.panic.panicscanner.application.engine;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TechnicalAnalysisEngineTest {

    private final Ta4jTechnicalAnalysisAdapter adapter = new Ta4jTechnicalAnalysisAdapter();

    @Test
    void shouldCalculateIndicatorsFromCandlesOrderedByDate() {
        List<Candle> candles = increasingCandles(21);
        Collections.reverse(candles);

        TechnicalAnalysis analysis = adapter.analyze("PETR4", "1D", candles);

        assertThat(analysis.ticker()).isEqualTo("PETR4");
        assertThat(analysis.analysisDate()).isEqualTo(LocalDate.of(2026, 1, 21));
        assertThat(analysis.timeframe()).isEqualTo("1D");
        assertThat(analysis.lastClose()).isEqualByComparingTo("21");
        assertThat(analysis.sma9()).isEqualByComparingTo("17.0000");
        assertThat(analysis.sma21()).isEqualByComparingTo("11.0000");
        assertThat(analysis.ema9()).isGreaterThan(analysis.sma21());
        assertThat(analysis.ema21()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(analysis.rsi9()).isEqualByComparingTo("100.0000");
        assertThat(analysis.atr14()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(analysis.adx14()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(analysis.averageVolume20()).isEqualByComparingTo("100.0000");
        assertThat(analysis.averageFinancialVolume20()).isEqualByComparingTo("1150.0000");
        assertThat(analysis.relativeVolume20()).isEqualByComparingTo("1.0000");
        assertThat(analysis.distanceFromSma21Percent()).isEqualByComparingTo("90.9091");
        assertThat(analysis.return5DaysPercent()).isEqualByComparingTo("31.2500");
        assertThat(analysis.return20DaysPercent()).isEqualByComparingTo("2000.0000");
        assertThat(analysis.highestHigh20()).isEqualByComparingTo("21.0000");
        assertThat(analysis.lowestLow20()).isEqualByComparingTo("2.0000");
        assertThat(analysis.trend()).isEqualTo(Trend.UPTREND);
    }

    @Test
    void shouldUseLastCandleDateAsAnalysisDate() {
        List<Candle> candles = increasingCandles(25);
        candles.set(24, new Candle(
                LocalDate.of(2026, 8, 2),
                new BigDecimal("25"),
                new BigDecimal("25"),
                new BigDecimal("25"),
                new BigDecimal("25"),
                BigDecimal.valueOf(100)));

        TechnicalAnalysis analysis = adapter.analyze("PETR4", "1D", candles);

        assertThat(analysis.analysisDate()).isEqualTo(LocalDate.of(2026, 8, 2));
    }

    @Test
    void shouldRejectInsufficientCandleHistory() {
        assertThatThrownBy(() -> adapter.analyze("PETR4", "1D", increasingCandles(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least 21 candles are required");
    }

    @Test
    void shouldDeclareImplementedAndPlaceholderIndicators() {
        assertThat(adapter.indicators()).contains(
                TechnicalIndicator.SMA,
                TechnicalIndicator.EMA,
                TechnicalIndicator.RSI,
                TechnicalIndicator.ATR,
                TechnicalIndicator.ADX,
                TechnicalIndicator.MACD);
        assertThat(TechnicalIndicator.SMA.implemented()).isTrue();
        assertThat(TechnicalIndicator.EMA.implemented()).isTrue();
        assertThat(TechnicalIndicator.RSI.implemented()).isTrue();
        assertThat(TechnicalIndicator.ATR.implemented()).isTrue();
        assertThat(TechnicalIndicator.ADX.implemented()).isTrue();
        assertThat(TechnicalIndicator.MACD.implemented()).isFalse();
    }

    @Test
    void shouldExcludeLatestCandleFromRelativeVolumeBaseline() {
        List<Candle> candles = increasingCandles(21);
        candles.set(20, new Candle(
                LocalDate.of(2026, 1, 21),
                new BigDecimal("21"),
                new BigDecimal("21"),
                new BigDecimal("21"),
                new BigDecimal("21"),
                BigDecimal.valueOf(200)));

        TechnicalAnalysis analysis = adapter.analyze("PETR4", "1D", candles);

        assertThat(analysis.averageVolume20()).isEqualByComparingTo("105.0000");
        assertThat(analysis.relativeVolume20()).isEqualByComparingTo("2.0000");
    }

    private List<Candle> increasingCandles(int amount) {
        List<Candle> candles = new ArrayList<>();
        LocalDate firstDate = LocalDate.of(2026, 1, 1);
        for (int i = 1; i <= amount; i++) {
            BigDecimal close = BigDecimal.valueOf(i);
            candles.add(new Candle(
                    firstDate.plusDays(i - 1),
                    close,
                    close,
                    close,
                    close,
                    BigDecimal.valueOf(100)));
        }
        return candles;
    }
}
