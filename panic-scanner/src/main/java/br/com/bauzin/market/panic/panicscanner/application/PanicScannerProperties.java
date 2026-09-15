package br.com.bauzin.market.panic.panicscanner.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Configuration used by the current momentum scanner strategy. */
@ConfigurationProperties(prefix = "panic-scanner.momentum")
public record PanicScannerProperties(
        BigDecimal minAverageFinancialVolume20,
        int lookbackDays,
        int maxTickersPerScan,
        BigDecimal minimumRsi,
        BigDecimal maximumRsi,
        BigDecimal minimumAdx,
        int minimumQualifiedScore,
        BigDecimal maximumHealthyDistanceFromSma21Percent,
        BigDecimal preferredRelativeVolume,
        int maxMarketScanResults) {

    public PanicScannerProperties {
        if (minAverageFinancialVolume20 == null) {
            minAverageFinancialVolume20 = new BigDecimal("20000000");
        }
        if (lookbackDays <= 0) {
            lookbackDays = 90;
        }
        if (maxTickersPerScan <= 0) {
            maxTickersPerScan = 20;
        }
        if (minimumRsi == null) {
            minimumRsi = BigDecimal.valueOf(50);
        }
        if (maximumRsi == null) {
            maximumRsi = BigDecimal.valueOf(70);
        }
        if (minimumAdx == null) {
            minimumAdx = BigDecimal.valueOf(20);
        }
        if (minimumQualifiedScore <= 0) {
            minimumQualifiedScore = 70;
        }
        if (maximumHealthyDistanceFromSma21Percent == null) {
            maximumHealthyDistanceFromSma21Percent = BigDecimal.valueOf(8);
        }
        if (preferredRelativeVolume == null) {
            preferredRelativeVolume = new BigDecimal("1.2");
        }
        if (maxMarketScanResults <= 0) {
            maxMarketScanResults = 50;
        }
    }
}
