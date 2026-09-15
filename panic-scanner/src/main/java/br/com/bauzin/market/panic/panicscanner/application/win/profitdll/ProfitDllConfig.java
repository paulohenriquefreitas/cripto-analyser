package br.com.bauzin.market.panic.panicscanner.application.win.profitdll;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "profitdll")
public record ProfitDllConfig(
        URI bridgeUrl,
        String ticker,
        String exchange,
        Duration connectionTimeout,
        Duration reconnectInitialBackoff,
        Duration reconnectMaxBackoff,
        Boolean mockWarmUpEnabled,
        Integer mockWarmUpCandles) {

    public ProfitDllConfig {
        if (bridgeUrl == null) bridgeUrl = URI.create("ws://127.0.0.1:8765");
        if (ticker == null || ticker.isBlank()) ticker = "WINFUT";
        if (exchange == null || exchange.isBlank()) exchange = "F";
        if (connectionTimeout == null) connectionTimeout = Duration.ofSeconds(10);
        if (reconnectInitialBackoff == null) reconnectInitialBackoff = Duration.ofSeconds(1);
        if (reconnectMaxBackoff == null) reconnectMaxBackoff = Duration.ofSeconds(30);
        if (mockWarmUpEnabled == null) mockWarmUpEnabled = true;
        if (mockWarmUpCandles == null || mockWarmUpCandles < 50) mockWarmUpCandles = 50;
    }
}
