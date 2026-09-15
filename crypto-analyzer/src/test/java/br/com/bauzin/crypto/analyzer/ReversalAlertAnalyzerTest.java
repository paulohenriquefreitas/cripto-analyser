package br.com.bauzin.crypto.analyzer;

import br.com.bauzin.crypto.enums.Direction;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.ReversalAlert;
import br.com.bauzin.crypto.model.ReversalAlertCandle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalAlertAnalyzerTest {

    private final ReversalAlertAnalyzer analyzer = new ReversalAlertAnalyzer();

    @Test
    void shouldAlertWhenThreeClosedCandlesShareBullishDirectionInsideWindow() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                bullish(baseTime.plusSeconds(60)),
                bullish(baseTime.plusSeconds(120)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(180),
                20);

        assertThat(alert).isPresent();
        assertThat(alert.get().getDirection()).isEqualTo(Direction.BULLISH);
        assertThat(alert.get().getAssetName()).isEqualTo("BITCOIN");
        assertThat(alert.get().getSuggestedAction()).isEqualTo("WATCH_FOR_PUT_ON_NEXT_CANDLE");
        assertThat(alert.get().getSecondsSinceSignal()).isEqualTo(0);
        assertThat(alert.get().getSignalCandles()).hasSize(3);
    }

    @Test
    void shouldNotAlertBeforeAlertWindow() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bearish(baseTime),
                bearish(baseTime.plusSeconds(60)),
                bearish(baseTime.plusSeconds(120)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(221),
                20);

        assertThat(alert).isEmpty();
    }

    @Test
    void shouldNotAlertWhenClosedSequenceBreaksDirection() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                bearish(baseTime.plusSeconds(60)),
                bullish(baseTime.plusSeconds(120)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(180),
                20);

        assertThat(alert).isEmpty();
    }

    @Test
    void shouldNotAlertWhenDojiBreaksLastThreeCandles() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                doji(baseTime.plusSeconds(60)),
                bullish(baseTime.plusSeconds(120)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(180),
                20);

        assertThat(alert).isEmpty();
    }

    @Test
    void shouldNotTreatSmallBodyDojiAsBullishOrBearish() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                bullish(baseTime.plusSeconds(60)),
                smallBodyDoji(baseTime.plusSeconds(120)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(180),
                20);

        assertThat(alert).isEmpty();
    }

    @Test
    void shouldRestartCountAfterDojiAndAlertOnThirdCandleAfterIt() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                doji(baseTime.plusSeconds(60)),
                bearish(baseTime.plusSeconds(120)),
                bearish(baseTime.plusSeconds(180)),
                bearish(baseTime.plusSeconds(240)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(300),
                20);

        assertThat(alert).isPresent();
        assertThat(alert.get().getDirection()).isEqualTo(Direction.BEARISH);
        assertThat(alert.get().getSignalCandles())
                .extracting(ReversalAlertCandle::getDirection)
                .containsExactly(Direction.BEARISH, Direction.BEARISH, Direction.BEARISH);
    }

    @Test
    void shouldNotAlertAgainWhenCurrentSequenceAlreadyHasMoreThanThreeCandles() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> closedCandles = List.of(
                bullish(baseTime),
                bullish(baseTime.plusSeconds(60)),
                bullish(baseTime.plusSeconds(120)),
                bullish(baseTime.plusSeconds(180)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                closedCandles,
                baseTime.plusSeconds(240),
                20);

        assertThat(alert).isEmpty();
    }

    @Test
    void shouldIgnoreCurrentOpenCandleWhenCheckingThreeClosedCandles() {
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");
        List<Candle> candles = List.of(
                bearish(baseTime),
                bullish(baseTime.plusSeconds(60)),
                bullish(baseTime.plusSeconds(120)),
                bullish(baseTime.plusSeconds(180)));

        Optional<ReversalAlert> alert = analyzer.analyze(
                "BTCUSDT",
                "1m",
                candles,
                baseTime.plusSeconds(195),
                20);

        assertThat(alert).isEmpty();
    }

    private Candle bullish(Instant openTime) {
        return candle(openTime, BigDecimal.ONE, BigDecimal.TEN);
    }

    private Candle bearish(Instant openTime) {
        return candle(openTime, BigDecimal.TEN, BigDecimal.ONE);
    }

    private Candle doji(Instant openTime) {
        return candle(openTime, BigDecimal.TEN, BigDecimal.TEN);
    }

    private Candle smallBodyDoji(Instant openTime) {
        return Candle.builder()
                .openTime(openTime)
                .closeTime(openTime.plusSeconds(60).minusMillis(1))
                .open(new BigDecimal("100.00"))
                .high(new BigDecimal("105.00"))
                .low(new BigDecimal("95.00"))
                .close(new BigDecimal("100.50"))
                .volume(BigDecimal.ONE)
                .build();
    }

    private Candle candle(Instant openTime, BigDecimal open, BigDecimal close) {
        return Candle.builder()
                .openTime(openTime)
                .closeTime(openTime.plusSeconds(60).minusMillis(1))
                .open(open)
                .high(BigDecimal.TEN)
                .low(BigDecimal.ONE)
                .close(close)
                .volume(BigDecimal.ONE)
                .build();
    }
}
