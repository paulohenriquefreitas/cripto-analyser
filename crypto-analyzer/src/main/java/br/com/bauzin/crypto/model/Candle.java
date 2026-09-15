package br.com.bauzin.crypto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {

    private static final BigDecimal DOJI_BODY_TO_RANGE_THRESHOLD = new BigDecimal("0.10");

    /**
     * Timestamp de abertura do candle
     */
    private Instant openTime;

    /**
     * Timestamp de fechamento do candle
     */
    private Instant closeTime;

    /**
     * Preço de abertura
     */
    private BigDecimal open;

    /**
     * Máxima
     */
    private BigDecimal high;

    /**
     * Mínima
     */
    private BigDecimal low;

    /**
     * Preço de fechamento
     */
    private BigDecimal close;

    /**
     * Volume negociado
     */
    private BigDecimal volume;

    /**
     * Volume financeiro
     */
    private BigDecimal quoteAssetVolume;

    /**
     * Número de negociações
     */
    private Long trades;

    /**
     * Volume comprado pelos takers
     */
    private BigDecimal takerBuyBaseVolume;

    /**
     * Volume financeiro comprado pelos takers
     */
    private BigDecimal takerBuyQuoteVolume;

    @Builder.Default
    private Boolean bullish = null;

    @Builder.Default
    private BigDecimal body = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal upperShadow = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal lowerShadow = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal range = BigDecimal.ZERO;

    private String source;

    public boolean isBullish() {
        return !isDoji() && close.compareTo(open) > 0;
    }

    public boolean isBearish() {
        return !isDoji() && close.compareTo(open) < 0;
    }

    public boolean isDoji() {
        if (open == null || close == null) {
            return false;
        }

        if (close.compareTo(open) == 0) {
            return true;
        }

        if (high == null || low == null || high.compareTo(low) <= 0) {
            return false;
        }

        BigDecimal bodySize = close.subtract(open).abs();
        BigDecimal rangeSize = high.subtract(low).abs();

        return bodySize.compareTo(rangeSize.multiply(DOJI_BODY_TO_RANGE_THRESHOLD)) <= 0;
    }

}
