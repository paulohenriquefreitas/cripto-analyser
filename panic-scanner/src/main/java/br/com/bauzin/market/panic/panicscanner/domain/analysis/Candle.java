package br.com.bauzin.market.panic.panicscanner.domain.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Immutable daily OHLCV candle used by scanner analysis. */
public record Candle(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        CandleStatus status,
        OffsetDateTime marketDataUpdatedAt) {

    public Candle(LocalDate date,
                  BigDecimal open,
                  BigDecimal high,
                  BigDecimal low,
                  BigDecimal close,
                  BigDecimal volume) {
        this(date, open, high, low, close, volume, CandleStatus.CLOSED, null);
    }

    public Candle {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(open, "open must not be null");
        Objects.requireNonNull(high, "high must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(close, "close must not be null");
        Objects.requireNonNull(volume, "volume must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public BigDecimal financialVolume() {
        return close.multiply(volume);
    }
}
