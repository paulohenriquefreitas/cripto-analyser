package br.com.bauzin.crypto.analyzer;

import br.com.bauzin.crypto.enums.Direction;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.Sequence;
import br.com.bauzin.crypto.model.SequenceResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TODO:
 * - Contar sequências de alta/baixa
 * - Encontrar maior sequência
 * - Estatísticas por horário
 * - Mercado flat x tendência
 */
@Service
public class SequenceAnalyzer {

    public SequenceResult analyze(List<Candle> candles) {

        List<Sequence> sequences =
                buildSequences(candles);

        int bullish = 0;
        int bearish = 0;
        int doji = 0;

        for (Candle candle : candles) {

            int compare = candle.getClose().compareTo(candle.getOpen());

            if (compare > 0) {
                bullish++;
            } else if (compare < 0) {
                bearish++;
            } else {
                doji++;
            }
        }

        return SequenceResult.builder()
                .sequences(sequences)
                .totalCandles(candles.size())
                .totalSequences(sequences.size())
                .bullishCandles(bullish)
                .bearishCandles(bearish)
                .dojiCandles(doji)
                .largestBullishSequence(findLargestBullish(sequences))
                .largestBearishSequence(findLargestBearish(sequences))
                .bullishSequenceDistribution(buildDistribution(sequences, Direction.BULLISH))
                .bearishSequenceDistribution(buildDistribution(sequences, Direction.BEARISH))
                .build();
    }

    private Integer findLargestBullish(List<Sequence> sequences) {

        return sequences.stream()
                .filter(s -> s.getDirection() == Direction.BULLISH)
                .map(Sequence::getLength)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private Integer findLargestBearish(List<Sequence> sequences) {

        return sequences.stream()
                .filter(s -> s.getDirection() == Direction.BEARISH)
                .map(Sequence::getLength)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private Map<Integer, Integer> buildDistribution(List<Sequence> sequences, Direction direction) {
        Map<Integer, Integer> distribution = new TreeMap<>();

        for (Sequence sequence : sequences) {
            if (sequence.getDirection() != direction) {
                continue;
            }

            distribution.merge(sequence.getLength(), 1, Integer::sum);
        }

        return distribution;
    }

    private List<Sequence> buildSequences(List<Candle> candles) {

        List<Sequence> sequences = new ArrayList<>();

        if (candles == null || candles.isEmpty()) {
            return sequences;
        }

        Direction currentDirection = getDirection(candles.get(0));

        int startIndex = 0;
        int length = 1;

        for (int i = 1; i < candles.size(); i++) {

            Candle candle = candles.get(i);
            Direction direction = getDirection(candle);

            if (direction == currentDirection) {
                length++;
                continue;
            }

            // Salva a sequência que acabou
            sequences.add(
                    Sequence.builder()
                            .direction(currentDirection)
                            .length(length)
                            .startIndex(startIndex)
                            .endIndex(i - 1)
                            .startTime(candles.get(startIndex).getOpenTime())
                            .endTime(candles.get(i - 1).getCloseTime())
                            .build()
            );

            // Inicia nova sequência
            currentDirection = direction;
            startIndex = i;
            length = 1;
        }

        // Salva a última sequência
        sequences.add(
                Sequence.builder()
                        .direction(currentDirection)
                        .length(length)
                        .startIndex(startIndex)
                        .endIndex(candles.size() - 1)
                        .startTime(candles.get(startIndex).getOpenTime())
                        .endTime(candles.get(candles.size() - 1).getCloseTime())
                        .build()
        );

        return sequences;
    }

    private Direction getDirection(Candle candle) {

        if (candle.isBullish()) {
            return Direction.BULLISH;
        }

        if (candle.isBearish()) {
            return Direction.BEARISH;
        }

        return Direction.DOJI;
    }
}
