package br.com.bauzin.crypto.model;

import java.util.Arrays;
import java.util.Locale;

public enum SupportedAsset {
    BTCUSDT,
    ETHUSDT,
    BNBUSDT,
    SOLUSDT;

    public static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);

        return Arrays.stream(values())
                .filter(asset -> asset.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported symbol: " + value))
                .name();
    }
}
