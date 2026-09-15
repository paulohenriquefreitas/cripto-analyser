package br.com.bauzin.market.panic.panicscanner.domain.win;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

public record WinCandle(
        OffsetDateTime timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal financialVolume,
        long tradeCount,
        OffsetDateTime startTime,
        OffsetDateTime endTime) {

    public WinCandle(OffsetDateTime timestamp,
                     BigDecimal open,
                     BigDecimal high,
                     BigDecimal low,
                     BigDecimal close,
                     BigDecimal volume) {
        this(timestamp, open, high, low, close, volume, close.multiply(volume), 1, timestamp, timestamp);
    }

    public WinCandle {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(close, "close must not be null");
        Objects.requireNonNull(volume, "volume must not be null");
        Objects.requireNonNull(financialVolume, "financialVolume must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
    }

    public boolean bullish() {
        return close.compareTo(open) > 0;
    }

    public boolean bearish() {
        return close.compareTo(open) < 0;
    }
}
