package br.com.bauzin.crypto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SequenceResult {

    /**
     * Todas as sequências encontradas na análise.
     */
    @Builder.Default
    private List<Sequence> sequences = new ArrayList<>();

    /**
     * Quantidade total de candles analisados.
     */
    private Integer totalCandles;

    /**
     * Quantidade de sequências encontradas.
     */
    private Integer totalSequences;

    /**
     * Maior sequência de alta.
     */
    private Integer largestBullishSequence;

    /**
     * Maior sequência de baixa.
     */
    private Integer largestBearishSequence;

    @Builder.Default
    private Map<Integer, Integer> bullishSequenceDistribution = new TreeMap<>();

    @Builder.Default
    private Map<Integer, Integer> bearishSequenceDistribution = new TreeMap<>();

    private Integer bullishCandles;
    private Integer bearishCandles;
    private Integer dojiCandles;

}
