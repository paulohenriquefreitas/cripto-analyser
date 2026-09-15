package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record WinCandleIngestionRequest(
        @NotBlank String contract,
        @NotBlank String timeframe,
        boolean append,
        @NotEmpty List<@Valid CandlePayload> candles) {

    public List<WinCandle> toCandles() {
        return candles.stream()
                .map(candle -> new WinCandle(
                        candle.timestamp(),
                        candle.open(),
                        candle.high(),
                        candle.low(),
                        candle.close(),
                        candle.volume()))
                .toList();
    }

    public record CandlePayload(
            @NotNull OffsetDateTime timestamp,
            @NotNull BigDecimal open,
            @NotNull BigDecimal high,
            @NotNull BigDecimal low,
            @NotNull BigDecimal close,
            @NotNull @PositiveOrZero BigDecimal volume) {
    }
}
