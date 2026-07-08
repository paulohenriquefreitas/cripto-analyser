package br.com.bauzin.crypto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "crypto_prices")
@CompoundIndex(name = "uk_symbol_interval_timestamp", def = "{'symbol': 1, 'interval': 1, 'timestamp': 1}", unique = true)
public class CryptoPrice {

    @Id
    private String id;

    private String symbol;

    private String interval;

    private Instant timestamp;

    private BigDecimal price;

    private Instant closeTime;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;

    private BigDecimal volume;

    private BigDecimal quoteAssetVolume;

    private Long trades;

    private BigDecimal takerBuyBaseVolume;

    private BigDecimal takerBuyQuoteVolume;

    private String source;

    @CreatedDate
    private Instant createdAt;
}
