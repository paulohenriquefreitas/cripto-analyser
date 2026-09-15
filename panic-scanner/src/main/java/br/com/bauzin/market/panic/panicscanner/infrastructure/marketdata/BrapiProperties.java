package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

@ConfigurationProperties(prefix = "brapi")
public record BrapiProperties(
        String baseUrl,
        String token,
        LocalTime marketCloseTime,
        boolean currentDateCandleFinalizedAfterMarketClose) {

    public BrapiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://brapi.dev";
        }
        if (marketCloseTime == null) {
            marketCloseTime = LocalTime.of(18, 0);
        }
    }
}
