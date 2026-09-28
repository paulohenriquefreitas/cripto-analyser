package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Pullback09SetupTest {
    private static final long CANDLE_1 = 6_000_000L;
    private static final long CANDLE_2 = CANDLE_1 + 300_000;
    private static final long CANDLE_3 = CANDLE_2 + 300_000;
    private static final long CANDLE_4 = CANDLE_3 + 300_000;

    @Test
    void closeAboveWithoutStartingBelowDoesNotCreateCrz() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 105, 100);
        feed.tick(CANDLE_1 + 1, 99, 100);
        feed.tick(CANDLE_1 + 2, 110, 100);

        assertThat(feed.tick(CANDLE_2, 110, 100)).isEmpty();
    }

    @Test
    void realUpwardCrossClosingAboveCreatesCrzOnCandleClose() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 90, 100);
        feed.tick(CANDLE_1 + 1, 110, 100);

        List<Pullback09Setup.Event> events = feed.tick(CANDLE_2, 110, 100);
        assertThat(events).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.CRZ09_UP);
        assertThat(feed.candle(CANDLE_1).open()).isLessThan(feed.candle(CANDLE_1).close());
        assertThat(events.getFirst().timeMsc()).isEqualTo(CANDLE_2);
        assertThat(events.getFirst().candleTimeMsc()).isEqualTo(CANDLE_1);
    }

    @Test
    void redCandleWithUpwardCrossDoesNotCreateCrz() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 110, 100);
        feed.tick(CANDLE_1 + 1, 90, 100);
        feed.tick(CANDLE_1 + 2, 105, 100);

        assertThat(feed.candle(CANDLE_1).close()).isLessThan(feed.candle(CANDLE_1).open());
        assertThat(feed.tick(CANDLE_2, 105, 100)).isEmpty();
    }

    @Test
    void redNextCandleTouchingSmaAndClosingAboveCreatesRj() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);

        List<Pullback09Setup.Event> events = feed.tick(CANDLE_3, 109, 100);
        assertThat(feed.candle(CANDLE_2).close()).isLessThan(feed.candle(CANDLE_2).open());
        assertThat(events).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(events.getFirst().candleTimeMsc()).isEqualTo(CANDLE_2);
    }

    @Test
    void candleWithoutSmaTouchDiscardsCandidate() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 148, 100);
        feed.tick(CANDLE_2 + 2, 149, 100);

        assertThat(feed.tick(CANDLE_3, 150, 100)).isEmpty();
    }

    @Test
    void interactionZoneIncludesThirtyPointsAndExcludesThirtyOne() {
        assertThat(rjEventsForDistance(31)).isEmpty();
        assertThat(rjEventsForDistance(30))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(rjEventsForDistance(29))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(rjEventsForDistance(0))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(rjEventsForDistance(-1))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
    }

    @Test
    void interactionUsesSma9CausalToEachLastRatherThanCandleCloseAverage() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 131, 100);
        feed.tick(CANDLE_2 + 2, 140, 109);

        assertThat(feed.tick(CANDLE_3, 140, 109)).isEmpty();
    }

    @Test
    void historicalPriceSequenceAtTwentyFourPointFourFourDistanceConfirmsOnce() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 189_000, 189_500);
        feed.tick(CANDLE_1 + 1, 189_510, 189_500);
        List<Pullback09Setup.Event> events = new ArrayList<>(
                feed.tick(CANDLE_2, 189_265, 189_200));

        feed.tick(CANDLE_2 + 1, 189_485, 189_400);
        double minDistance = 189_100 - 189_075.5556;
        assertThat(minDistance - 24.4444).isLessThan(0.0001);
        feed.tick(CANDLE_2 + 2, 189_100, 189_075.5556);
        feed.tick(CANDLE_2 + 3, 189_185, 189_085);
        events.addAll(feed.tick(CANDLE_3, 189_190, 189_100));
        events.addAll(feed.tick(CANDLE_3 + 1, 189_490, 189_200));
        events.addAll(feed.tick(CANDLE_3 + 2, 189_500, 189_200));
        assertThat(events).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.CRZ09_UP, Pullback09Setup.EventType.RJ09_UP);
        events.addAll(feed.tick(CANDLE_4, 189_100, 189_200));

        assertThat(events).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(
                        Pullback09Setup.EventType.CRZ09_UP,
                        Pullback09Setup.EventType.RJ09_UP,
                        Pullback09Setup.EventType.PULLB09_UP);
        assertThat(events.getLast().timeMsc()).isEqualTo(CANDLE_4);
        assertThat(events.getLast().candleTimeMsc()).isEqualTo(CANDLE_3);
    }

    @Test
    void greenCandleTouchingSmaAndClosingAboveDoesNotCreateRj() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 160, 100);

        assertThat(feed.candle(CANDLE_2).close()).isGreaterThan(feed.candle(CANDLE_2).open());
        assertThat(feed.tick(CANDLE_3, 161, 100)).isEmpty();
    }

    @Test
    void neutralCandleTouchingSmaAndClosingAboveDoesNotCreateRj() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 150, 100);

        assertThat(feed.candle(CANDLE_2).close()).isEqualTo(feed.candle(CANDLE_2).open());
        assertThat(feed.tick(CANDLE_3, 151, 100)).isEmpty();
    }

    @Test
    void closeBelowSmaDiscardsCandidateWithoutRj() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 95, 100);
        feed.tick(CANDLE_2 + 2, 99, 100);

        assertThat(feed.tick(CANDLE_3, 111, 100)).isEmpty();
    }

    @Test
    void thirdCandleBreakoutConfirmsExactlyOnePullback() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        assertThat(feed.tick(CANDLE_3, 149, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);

        assertThat(feed.tick(CANDLE_3 + 1, 151, 100)).isEmpty();
        List<Pullback09Setup.Event> confirmation = feed.tick(CANDLE_4 + 7, 80, 100);
        assertThat(confirmation).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.PULLB09_UP);
        assertThat(confirmation.getFirst().timeMsc()).isEqualTo(CANDLE_4 + 7);
        assertThat(confirmation.getFirst().candleTimeMsc()).isEqualTo(CANDLE_3);
        assertThat(confirmation.getFirst().price()).isEqualTo(151);
    }

    @Test
    void multipleLastPricesAboveRejectionHighPublishOnlyOnePullback() {
        Feed feed = confirmedCandidate();
        List<Pullback09Setup.Event> events = new ArrayList<>();
        events.addAll(feed.tick(CANDLE_3, 151, 100));
        events.addAll(feed.tick(CANDLE_3 + 1, 152, 100));
        events.addAll(feed.tick(CANDLE_3 + 2, 153, 100));
        assertThat(events).isEmpty();
        events.addAll(feed.tick(CANDLE_4, 154, 100));
        events.addAll(feed.tick(CANDLE_4 + 1, 155, 100));
        events.addAll(feed.tick(CANDLE_4 + 300_000, 156, 100));

        assertThat(events).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.PULLB09_UP);
    }

    @Test
    void candleThreeWithoutBreakoutExpiresCandidate() {
        Feed feed = confirmedCandidate();
        feed.tick(CANDLE_3, 150, 100);
        feed.tick(CANDLE_3 + 1, 149, 100);

        assertThat(feed.tick(CANDLE_3 + 300_000, 108, 100)).isEmpty();
    }

    @Test
    void anotherSetupRequiresANewCrzAfterConfirmation() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        assertThat(feed.tick(CANDLE_3, 90, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(feed.tick(CANDLE_3 + 1, 151, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_4, 90, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.PULLB09_UP);
        feed.tick(CANDLE_3 + 300_001, 110, 100);
        assertThat(feed.tick(CANDLE_3 + 600_000, 110, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.CRZ09_UP);
    }

    @Test
    void emittedEventRemainsUnchangedAsFuturePricesArrive() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 90, 100);
        feed.tick(CANDLE_1 + 1, 110, 100);
        List<Pullback09Setup.Event> emitted = feed.tick(CANDLE_2, 110, 100);
        Pullback09Setup.Event recorded = emitted.getFirst();
        feed.tick(CANDLE_2 + 1, 99, 100);
        feed.tick(CANDLE_2 + 2, 105, 100);
        feed.tick(CANDLE_3, 111, 100);

        assertThat(recorded).isEqualTo(emitted.getFirst());
        assertThat(recorded.eventType()).isEqualTo(Pullback09Setup.EventType.CRZ09_UP);
        assertThat(recorded.timeMsc()).isEqualTo(CANDLE_2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "B wick breakout closes below rejection, 149, 160, 140",
            "C red despite close above rejection, 170, 180, 160",
            "D green closes at rejection high, 140, 160, 150",
            "D green closes below rejection high, 140, 160, 145",
            "E green never breaks rejection high, 140, 150, 149",
            "E green stays below rejection high, 140, 149, 148",
            "neutral above rejection high, 160, 170, 160"
    })
    void failedConfirmationExpiresWithoutAllowingLaterCandles(
            String scenario, double open, double high, double close) {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        assertThat(feed.tick(CANDLE_3, open, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(feed.tick(CANDLE_3 + 1, high, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_3 + 2, close, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_4, 200, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_4 + 1, 210, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_4 + 300_000, 220, 100)).isEmpty();
    }

    @Test
    void missingCandleThreeCannotBeConfirmedByCandleFour() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        assertThat(feed.tick(CANDLE_4, 160, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        assertThat(feed.tick(CANDLE_4 + 1, 170, 100)).isEmpty();
        assertThat(feed.tick(CANDLE_4 + 300_000, 180, 100)).isEmpty();
    }

    private static Feed crzFeed() {
        Feed feed = new Feed();
        feed.tick(CANDLE_1, 90, 100);
        feed.tick(CANDLE_1 + 1, 150, 100);
        assertThat(feed.tick(CANDLE_2, 150, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.CRZ09_UP);
        return feed;
    }

    private static Feed confirmedCandidate() {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        assertThat(feed.tick(CANDLE_3, 149, 100))
                .extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_UP);
        return feed;
    }

    private static List<Pullback09Setup.Event> rjEventsForDistance(double distance) {
        Feed feed = crzFeed();
        feed.tick(CANDLE_2 + 1, 100 + distance, 100);
        feed.tick(CANDLE_2 + 2, 140, 100);
        return feed.tick(CANDLE_3, 140, 100);
    }

    private static final class Feed {
        private final Pullback09Setup setup = new Pullback09Setup();
        private long bucket = -1;
        private double open;
        private double high;
        private double low;
        private final Map<Long, IntrabarCandleSnapshot> candles = new HashMap<>();

        List<Pullback09Setup.Event> tick(long timeMsc, double price, double sma9) {
            long nextBucket = Math.floorDiv(timeMsc, 300_000) * 300_000;
            if (nextBucket != bucket) {
                bucket = nextBucket;
                open = high = low = price;
            } else {
                high = Math.max(high, price);
                low = Math.min(low, price);
            }
            IntrabarCandleSnapshot candle = new IntrabarCandleSnapshot(
                    "WINV26", bucket, open, high, low, price);
            candles.put(bucket, candle);
            return setup.onEvent(new IntrabarMarketState(
                    "WINV26", timeMsc, price, candle, sma9, 100.0, null));
        }

        IntrabarCandleSnapshot candle(long candleBucket) {
            return candles.get(candleBucket);
        }
    }
}
