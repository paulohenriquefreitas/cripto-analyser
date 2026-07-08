package br.com.bauzin.crypto.model;

import br.com.bauzin.crypto.enums.TrendDirection;
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
public class TrendAnalysisResult {

    private TrendDirection direction;

    private String strength;

    private String summary;

    private Integer analyzedCandles;

    private Instant startTime;

    private Instant endTime;

    private BigDecimal startPrice;

    private BigDecimal endPrice;

    private BigDecimal priceChange;

    private BigDecimal priceChangePercent;

    private Integer bullishCandles;

    private Integer bearishCandles;

    private Integer dojiCandles;

    private Integer largestBullishSequence;

    private Integer largestBearishSequence;

    private BigDecimal bullishRatioPercent;

    private BigDecimal bearishRatioPercent;
}
