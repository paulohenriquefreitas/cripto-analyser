package br.com.bauzin.crypto.analyzer;

import br.com.bauzin.crypto.enums.Direction;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.ReversalAlert;
import br.com.bauzin.crypto.model.ReversalAlertCandle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ReversalAlertAnalyzer {

    private static final int REQUIRED_CLOSED_CANDLES = 3;

    public Optional<ReversalAlert> analyze(String symbol,
                                           String interval,
                                           List<Candle> closedCandles,
                                           Instant now,
                                           int alertWindowSeconds) {
        List<Candle> fullyClosedCandles = filterFullyClosedCandles(closedCandles, now);

        if (fullyClosedCandles.size() < REQUIRED_CLOSED_CANDLES) {
            return Optional.empty();
        }

        Direction closedDirection = resolveDirection(fullyClosedCandles.getLast());

        if (closedDirection == Direction.DOJI) {
            return Optional.empty();
        }

        int sequenceStart = findCurrentSequenceStart(fullyClosedCandles, closedDirection);
        int sequenceLength = fullyClosedCandles.size() - sequenceStart;

        if (sequenceLength != REQUIRED_CLOSED_CANDLES) {
            return Optional.empty();
        }

        List<Candle> lastThreeClosed = fullyClosedCandles.subList(sequenceStart, fullyClosedCandles.size());

        Candle signalCandle = lastThreeClosed.getLast();
        Instant signalCloseBoundary = signalCandle.getCloseTime().plusMillis(1);
        long secondsSinceSignal = java.time.Duration.between(signalCloseBoundary, now).toSeconds();

        if (secondsSinceSignal < 0 || secondsSinceSignal > alertWindowSeconds) {
            return Optional.empty();
        }

        String suggestedAction = closedDirection == Direction.BULLISH
                ? "WATCH_FOR_PUT_ON_NEXT_CANDLE"
                : "WATCH_FOR_CALL_ON_NEXT_CANDLE";
        String assetName = resolveAssetName(symbol);
        String directionText = closedDirection == Direction.BULLISH ? "ALTA" : "BAIXA";

        return Optional.of(ReversalAlert.builder()
                .symbol(symbol)
                .assetName(assetName)
                .interval(interval)
                .direction(closedDirection)
                .suggestedAction(suggestedAction)
                .closedSequenceLength(REQUIRED_CLOSED_CANDLES)
                .secondsSinceSignal((int) secondsSinceSignal)
                .signalCandleOpenTime(signalCandle.getOpenTime())
                .signalCandleCloseTime(signalCandle.getCloseTime())
                .alertKey("%s:%s:%s:%s".formatted(symbol, interval, signalCandle.getOpenTime(), closedDirection))
                .message("%s (%s) fechou 3 candles em %s ha %ss. Acompanhe o quarto candle."
                        .formatted(assetName, symbol, directionText, secondsSinceSignal))
                .signalCandles(toSignalCandles(lastThreeClosed))
                .build());
    }

    private List<Candle> filterFullyClosedCandles(List<Candle> candles, Instant now) {
        if (candles == null || now == null) {
            return List.of();
        }

        return candles.stream()
                .filter(candle -> candle.getCloseTime() != null && candle.getCloseTime().isBefore(now))
                .toList();
    }

    private List<ReversalAlertCandle> toSignalCandles(List<Candle> candles) {
        return candles.stream()
                .map(candle -> ReversalAlertCandle.builder()
                        .openTime(candle.getOpenTime())
                        .closeTime(candle.getCloseTime())
                        .open(candle.getOpen())
                        .close(candle.getClose())
                        .direction(resolveDirection(candle))
                        .build())
                .toList();
    }

    private String resolveAssetName(String symbol) {
        return switch (symbol) {
            case "BTCUSDT" -> "BITCOIN";
            case "ETHUSDT" -> "ETHEREUM";
            case "BNBUSDT" -> "BNB";
            case "SOLUSDT" -> "SOLANA";
            default -> symbol;
        };
    }

    private int findCurrentSequenceStart(List<Candle> candles, Direction direction) {
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (resolveDirection(candles.get(i)) != direction) {
                return i + 1;
            }
        }

        return 0;
    }

    private Direction resolveDirection(Candle candle) {
        if (candle.isBullish()) {
            return Direction.BULLISH;
        }

        if (candle.isBearish()) {
            return Direction.BEARISH;
        }

        return Direction.DOJI;
    }
}
