package br.com.bauzin.market.panic.panicscanner.application.win;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "panic-scanner.win")
public record PanicScannerWinProperties(
        String contextTimeframe,
        String executionTimeframe,
        Rsi rsi,
        Trend trend,
        Extension extension,
        Pullback pullback,
        Execution execution,
        Risk risk,
        Consolidation consolidation,
        Breakout breakout) {

    public PanicScannerWinProperties {
        if (contextTimeframe == null || contextTimeframe.isBlank()) contextTimeframe = "5m";
        if (executionTimeframe == null || executionTimeframe.isBlank()) executionTimeframe = "1m";
        if (rsi == null) rsi = new Rsi(null, null);
        if (trend == null) trend = new Trend(null);
        if (extension == null) extension = new Extension(null, 0);
        if (pullback == null) pullback = new Pullback(null);
        if (execution == null) execution = new Execution(0);
        if (risk == null) risk = new Risk(null, null, null, null);
        if (consolidation == null) consolidation = new Consolidation(null, null);
        if (breakout == null) breakout = new Breakout(null, null);
    }

    public record Rsi(BigDecimal oversold, BigDecimal overbought) {
        public Rsi {
            if (oversold == null) oversold = BigDecimal.valueOf(30);
            if (overbought == null) overbought = BigDecimal.valueOf(70);
        }
    }

    public record Trend(BigDecimal minimumAdx) {
        public Trend {
            if (minimumAdx == null) minimumAdx = BigDecimal.valueOf(20);
        }
    }

    public record Extension(BigDecimal maximumEma21DistanceAtr, int consecutiveCandles) {
        public Extension {
            if (maximumEma21DistanceAtr == null) maximumEma21DistanceAtr = new BigDecimal("2.0");
            if (consecutiveCandles <= 0) consecutiveCandles = 3;
        }
    }

    public record Pullback(BigDecimal maximumDepthAtr) {
        public Pullback {
            if (maximumDepthAtr == null) maximumDepthAtr = new BigDecimal("1.5");
        }
    }

    public record Execution(int minimumScore) {
        public Execution {
            if (minimumScore <= 0) minimumScore = 75;
        }
    }

    public record Risk(BigDecimal minimumRewardRisk, BigDecimal atrStopBuffer, BigDecimal target1Atr, BigDecimal target2Atr) {
        public Risk {
            if (minimumRewardRisk == null) minimumRewardRisk = new BigDecimal("1.5");
            if (atrStopBuffer == null) atrStopBuffer = new BigDecimal("0.2");
            if (target1Atr == null) target1Atr = new BigDecimal("2.0");
            if (target2Atr == null) target2Atr = new BigDecimal("3.0");
        }
    }

    public record Consolidation(BigDecimal maximumWidthAtr, BigDecimal maximumSlopePercent) {
        public Consolidation {
            if (maximumWidthAtr == null) maximumWidthAtr = new BigDecimal("2.2");
            if (maximumSlopePercent == null) maximumSlopePercent = new BigDecimal("0.08");
        }
    }

    public record Breakout(BigDecimal minimumDistanceAtr, BigDecimal maximumDistanceAtr) {
        public Breakout {
            if (minimumDistanceAtr == null) minimumDistanceAtr = new BigDecimal("0.15");
            if (maximumDistanceAtr == null) maximumDistanceAtr = new BigDecimal("1.2");
        }
    }
}
