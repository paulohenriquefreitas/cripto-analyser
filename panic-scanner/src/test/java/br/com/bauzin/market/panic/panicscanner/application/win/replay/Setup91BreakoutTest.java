package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static br.com.bauzin.market.panic.panicscanner.application.win.replay.Setup91Breakout.EventType.*;
import static org.assertj.core.api.Assertions.*;

class Setup91BreakoutTest {
    private static final long START = 6_000_000;
    private static final long M5 = 300_000;
    private static final long SIGNAL = START + 14 * M5;
    private static final long NEXT = SIGNAL + M5;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void armsClosedSignalAndTriggersOnlyFirstStrictBreakout(boolean buy) {
        var feed = signal(buy);
        var update = feed.tick(NEXT, 100);
        assertThat(update.events()).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_ARMED : SETUP91_SELL_ARMED);
        var signal = update.events().getFirst().signal();
        assertThat(signal.side()).isEqualTo(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
        assertThat(signal.candle().bucketStartTimeMsc()).isEqualTo(SIGNAL);
        assertThat(signal.candle().open()).isEqualTo(100);
        assertThat(signal.candle().high()).isEqualTo(120);
        assertThat(signal.candle().low()).isEqualTo(80);
        assertThat(signal.candle().close()).isEqualTo(buy ? 110 : 90);
        assertThat(signal.ema9()).isCloseTo(buy ? 100.4 : 99.6, within(1e-10));
        assertThat(signal.previousSlope()).isEqualTo(buy ? -2 : 2);
        assertThat(signal.currentSlope()).isCloseTo(buy ? 2.4 : -2.4, within(1e-10));
        assertThat(signal.previousFiveEma9()).containsExactlyElementsOf(update.observation().orElseThrow().previousFiveEma9());
        assertThat(signal.previousFiveDirection()).isEqualTo(buy ? Setup91.Direction.DOWN : Setup91.Direction.UP);
        assertThat(feed.tick(NEXT, buy ? 120 : 80).events()).isEmpty();
        assertThat(feed.breakout.armedSignals()).containsExactly(signal);
        var triggered = feed.tick(NEXT, buy ? 121 : 79).events();
        assertThat(triggered).hasSize(1);
        assertThat(triggered.getFirst().type()).isEqualTo(buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
        assertThat(triggered.getFirst().signal()).isEqualTo(signal);
        assertThat(triggered.getFirst().sequence()).isEqualTo(update.events().getFirst().sequence() + 2);
        assertThat(triggered.getFirst().timeMsc()).isEqualTo(NEXT);
        assertThat(triggered.getFirst().lastPrice()).isEqualTo(buy ? 121 : 79);
        assertThat(feed.breakout.armedSignals()).isEmpty();
        assertThat(feed.tick(NEXT + 1, buy ? 130 : 70).events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void signalCannotTriggerItselfAndNoBreakoutLeavesItArmed(boolean buy) {
        // signal() asserts no event for every LAST inside the reversal candle, including its extremes.
        var feed = signal(buy);
        assertThat(feed.breakout.armedSignals()).isEmpty();
        feed.tick(NEXT, 100);
        for (double price : new double[]{100, 119, 81, 120, 80, 100}) {
            assertThat(feed.tick(NEXT + 1, price).events()).isEmpty();
        }
        assertThat(feed.breakout.armedSignals()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rolloverLastCanArmThenTriggerWithSameSequence(boolean buy) {
        var feed = signal(buy);
        var update = feed.tick(NEXT + 7, buy ? 121 : 79);
        assertThat(update.events()).extracting(Setup91Breakout.Event::type).containsExactly(
                buy ? SETUP91_BUY_ARMED : SETUP91_SELL_ARMED,
                buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
        assertThat(update.events().getFirst().sequence()).isEqualTo(update.events().getLast().sequence());
        assertThat(update.events().getLast().timeMsc()).isEqualTo(NEXT + 7);
        assertThat(update.observation().orElseThrow().ema9()).isCloseTo(buy ? 100.4 : 99.6, within(1e-10));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOutOfOrderLastsBeforeChangingArmedState(boolean buy) {
        var feed = signal(buy);
        feed.tick(NEXT, 100);
        long sequence = feed.sequence;
        var bad = new IntrabarMarketState("WINFUT", NEXT, buy ? 121 : 79,
                feed.processor.currentCandle(), null, null, null);
        assertThatIllegalArgumentException().isThrownBy(() -> feed.breakout.onEvent(bad, sequence));
        assertThatIllegalArgumentException().isThrownBy(() -> feed.breakout.onEvent(bad, sequence - 1));
        var old = new IntrabarMarketState("WINFUT", NEXT - 1, bad.price(),
                feed.processor.currentCandle(), null, null, null);
        assertThatIllegalArgumentException().isThrownBy(() -> feed.breakout.onEvent(old, sequence + 1));
        assertThat(feed.breakout.armedSignals()).hasSize(1);
        assertThat(feed.tick(NEXT, bad.price()).events()).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void matchingClosedDirectionStillExpiresBeforeNPlusTwoBreakout(boolean buy) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        assertThat(feed.tick(NEXT + 1, buy ? 110 : 90).events()).isEmpty();
        var closed = feed.tick(NEXT + M5, buy ? 121 : 79);
        assertThat(closed.observation().orElseThrow().direction())
                .isEqualTo(buy ? Setup91.Direction.UP : Setup91.Direction.DOWN);
        assertThat(closed.events()).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        assertThat(closed.events().getFirst().signal()).isEqualTo(original);
        assertThat(closed.events().getFirst().timeMsc()).isEqualTo(NEXT + M5);
        assertThat(feed.breakout.armedSignals()).isEmpty();
        // Equal timestamp, increasing sequence: expiry is final for every LAST in N+2.
        assertThat(feed.tick(NEXT + M5, buy ? 130 : 70).events()).isEmpty();
        assertThat(feed.tick(NEXT + 2 * M5, buy ? 140 : 60).events())
                .noneMatch(event -> event.signal().equals(original));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void laterFlatOrOppositeCloseCancelsAndPreventsOldBreakout(boolean buy, boolean flat) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        double finalPrice = flat ? original.ema9() : (buy ? 90 : 110);
        assertThat(feed.tick(NEXT + 1, finalPrice).events()).isEmpty();
        assertThat(feed.breakout.armedSignals()).containsExactly(original);
        // Even a breakout price on the rollover belongs to the NEW candle, after cancellation.
        var closed = feed.tick(NEXT + M5, buy ? 121 : 79);
        assertThat(closed.observation().orElseThrow().direction()).isEqualTo(flat
                ? Setup91.Direction.FLAT : (buy ? Setup91.Direction.DOWN : Setup91.Direction.UP));
        var oldEvents = closed.events().stream().filter(event -> event.signal().equals(original)).toList();
        assertThat(oldEvents).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        assertThat(oldEvents.getFirst().timeMsc()).isEqualTo(NEXT + M5);
        assertThat(oldEvents.getFirst().sequence()).isEqualTo(feed.sequence);
        assertThat(feed.breakout.armedSignals()).doesNotContain(original);
        assertThat(feed.tick(NEXT + M5, buy ? 130 : 70).events())
                .noneMatch(event -> event.signal().equals(original));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void intrabarTriggerIsFinalDespiteLaterFlatOrOppositeClose(boolean buy, boolean flat) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        var trigger = feed.tick(NEXT + 1, buy ? 121 : 79).events().getFirst();
        assertThat(trigger.type()).isEqualTo(buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
        assertThat(trigger.signal()).isEqualTo(original);
        assertThat(feed.breakout.armedSignals()).doesNotContain(original);
        // Same millisecond, later LAST sequence: the final close must not rewrite the earlier trigger.
        assertThat(feed.tick(NEXT + 1, flat ? original.ema9() : (buy ? 90 : 110)).events()).isEmpty();
        var closed = feed.tick(NEXT + M5, 100);
        assertThat(closed.observation().orElseThrow().direction()).isEqualTo(flat
                ? Setup91.Direction.FLAT : (buy ? Setup91.Direction.DOWN : Setup91.Direction.UP));
        assertThat(closed.events()).noneMatch(event -> event.signal().equals(original));
        assertThat(feed.breakout.armedSignals()).doesNotContain(original);
        assertThat(feed.tick(NEXT + M5 + 1, buy ? 130 : 70).events())
                .noneMatch(event -> event.signal().equals(original));
        assertThat(trigger.type()).isEqualTo(buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
        assertThat(trigger.timeMsc()).isEqualTo(NEXT + 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactTouchAtEndOfNPlusOneExpiresOnFirstLastOfNPlusTwo(boolean buy) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        assertThat(feed.tick(NEXT + M5 - 1, buy ? 120 : 80).events()).isEmpty();
        assertThat(feed.breakout.armedSignals()).containsExactly(original);
        var expired = feed.tick(NEXT + M5, buy ? 121 : 79).events();
        assertThat(expired).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        assertThat(expired.getFirst().signal()).isEqualTo(original);
        assertThat(feed.breakout.armedSignals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void breakoutOnLastMillisecondOfNPlusOneIsFinal(boolean buy) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        var triggered = feed.tick(NEXT + M5 - 1, buy ? 120.001 : 79.999).events();
        assertThat(triggered).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_TRIGGERED : SETUP91_SELL_TRIGGERED);
        assertThat(feed.tick(NEXT + M5, 100).events()).noneMatch(e -> e.signal().equals(original));
        assertThat(feed.breakout.armedSignals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void skippedExecutionBucketCannotExtendNewlyDiscoveredSignal(boolean buy) {
        var feed = signal(buy);
        var late = feed.tick(NEXT + M5, buy ? 121 : 79).events();
        assertThat(late).extracting(Setup91Breakout.Event::type).containsExactly(
                buy ? SETUP91_BUY_ARMED : SETUP91_SELL_ARMED,
                buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        assertThat(late.getFirst().signal()).isEqualTo(late.getLast().signal());
        assertThat(late.getFirst().sequence()).isEqualTo(late.getLast().sequence());
        assertThat(feed.breakout.armedSignals()).isEmpty();
        assertThat(feed.tick(NEXT + M5, buy ? 130 : 70).events()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void gapAfterArmingExpiresBeforeLaterPriceCanBreakOut(boolean buy) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        feed.tick(NEXT + 1, buy ? 110 : 90);
        var expired = feed.tick(NEXT + 3 * M5, buy ? 121 : 79).events();
        assertThat(expired).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        assertThat(expired.getFirst().signal()).isEqualTo(original);
        assertThat(feed.breakout.armedSignals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oppositeReversalCannotRetainOrResurrectExpiredCandidate(boolean buy) {
        var feed = signal(buy);
        var original = feed.tick(NEXT, 100).events().getFirst().signal();
        feed.tick(NEXT + 1, buy ? 110 : 90);
        var expiry = feed.tick(NEXT + M5, buy ? 112 : 88).events();
        assertThat(expiry).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_BUY_CANCELLED : SETUP91_SELL_CANCELLED);
        double[] closes = {114, 116, 118, 90};
        for (int i = 0; i < closes.length; i++) {
            if (i == 2) feed.tick(NEXT + (i + 2) * M5, buy ? 110 : 90);
            feed.tick(NEXT + (i + 2) * M5, buy ? closes[i] : 200 - closes[i]);
            assertThat(feed.breakout.armedSignals()).isEmpty();
        }
        // Close the opposite reversal, while the LAST crosses the original expired extreme.
        var opposite = feed.tick(NEXT + 6 * M5, buy ? 121 : 79).events();
        assertThat(opposite).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_SELL_ARMED : SETUP91_BUY_ARMED);
        var replacement = opposite.getFirst().signal();
        assertThat(replacement).isNotEqualTo(original);
        assertThat(feed.breakout.armedSignals()).containsExactly(replacement);
        var trigger = feed.tick(NEXT + 6 * M5, buy ? 89 : 111).events();
        assertThat(trigger).extracting(Setup91Breakout.Event::type)
                .containsExactly(buy ? SETUP91_SELL_TRIGGERED : SETUP91_BUY_TRIGGERED);
        assertThat(trigger.getFirst().signal()).isEqualTo(replacement);
        assertThat(feed.breakout.armedSignals()).isEmpty();
        assertThat(feed.tick(NEXT + 7 * M5, buy ? 130 : 70).events())
                .noneMatch(e -> e.signal().equals(original) || e.signal().equals(replacement));
    }

    private static Feed signal(boolean buy) {
        var feed = new Feed();
        for (int i = 0; i < 9; i++) assertThat(feed.tick(START + i * M5, 100).events()).isEmpty();
        double[] trendCloses = {106, 96, 94, 92, 90};
        for (int i = 0; i < trendCloses.length; i++) {
            if (i == 4) assertThat(feed.tick(START + (9 + i) * M5, 100).events()).isEmpty();
            assertThat(feed.tick(START + (9 + i) * M5, buy ? trendCloses[i] : 200 - trendCloses[i]).events()).isEmpty();
        }
        assertThat(feed.tick(SIGNAL, 100).events()).isEmpty();
        assertThat(feed.tick(SIGNAL + 1, 120).events()).isEmpty();
        assertThat(feed.tick(SIGNAL + 1, 80).events()).isEmpty();
        assertThat(feed.tick(SIGNAL + 1, buy ? 110 : 90).events()).isEmpty();
        return feed;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void positionGateSuppressesReversalWithoutSkippingClosedEma(boolean buy) {
        var baseline = signal(buy);
        var blocked = signal(buy);
        double price = buy ? 121 : 79;
        var expected = baseline.tick(NEXT, price);
        var actual = blocked.tick(NEXT, price, true);
        assertThat(expected.events()).hasSize(2);
        assertThat(actual.events()).isEmpty();
        assertThat(actual.observation().orElseThrow().candidate()).isEmpty();
        assertThat(actual.observation().orElseThrow().reversal())
                .contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
        assertThat(actual.observation().orElseThrow().ema9())
                .isEqualTo(expected.observation().orElseThrow().ema9());
        assertThat(blocked.breakout.armedSignals()).isEmpty();
        assertThat(blocked.tick(NEXT + 1, price).events()).isEmpty(); // No stale signal on resume.
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void positionGateDiscardsAlreadyArmedSignalWithoutCancellationNoise(boolean buy) {
        var feed = signal(buy);
        feed.tick(NEXT, 100);
        assertThat(feed.breakout.armedSignals()).hasSize(1);
        assertThat(feed.tick(NEXT + 1, buy ? 121 : 79, true).events()).isEmpty();
        assertThat(feed.breakout.armedSignals()).isEmpty();
        assertThat(feed.tick(NEXT + 2, buy ? 121 : 79).events()).isEmpty();
    }

    private static final class Feed {
        final IntrabarM5Processor processor = new IntrabarM5Processor();
        final Setup91Breakout breakout = new Setup91Breakout();
        long sequence;

        Setup91Breakout.Update tick(long time, double price) {
            return tick(time, price, false);
        }

        Setup91Breakout.Update tick(long time, double price, boolean positionOpen) {
            return breakout.onEvent(processor.onEvent(new CanonicalPriceEvent("WINFUT", time, price)), ++sequence, positionOpen);
        }
    }
}
