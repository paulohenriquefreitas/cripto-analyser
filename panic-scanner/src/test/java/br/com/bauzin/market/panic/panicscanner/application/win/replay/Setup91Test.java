package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class Setup91Test {
    private static final long START = 6_000_000;
    private static final long M5 = M5Bucket.DURATION_MSC;

    @Test
    void downToUpProducesBuyWithBothEmaValuesAndSlopes() {
        var setup = warmedSetup(0);
        close(setup, 0, 106);
        close(setup, 1, 96);
        close(setup, 2, 94);
        close(setup, 3, 92);
        var down = close(setup, 4, 90);
        var reversal = close(setup, 5, 110);
        assertThat(down.direction()).isEqualTo(Setup91.Direction.DOWN);
        assertThat(down.candidate()).isEmpty();
        assertThat(reversal.previousDirection()).isEqualTo(Setup91.Direction.DOWN);
        assertThat(reversal.direction()).isEqualTo(Setup91.Direction.UP);
        assertThat(reversal.candidate()).contains(Setup91.Candidate.BUY);
        assertThat(reversal.previousFiveEma9()).containsExactly(106.0, 104.0, 102.0, 100.0, 98.0);
        assertThat(reversal.previousFiveDirection()).isEqualTo(Setup91.Direction.DOWN);
        // EMA alpha = 2/(9+1) = 0.2; these values distinguish EMA from SMA.
        assertThat(reversal.previousEma9()).isEqualTo(98.0);
        assertThat(reversal.ema9()).isCloseTo(100.4, within(1e-10));
        assertThat(reversal.previousSlope()).isEqualTo(-2.0);
        assertThat(reversal.currentSlope()).isCloseTo(2.4, within(1e-10));
        assertThat(close(setup, 6, 120).candidate()).isEmpty();
        assertThat(reversal.previousFiveEma9()).containsExactly(106.0, 104.0, 102.0, 100.0, 98.0);
        assertThatThrownBy(() -> reversal.previousFiveEma9().set(0, 0.0)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(reversal.ema9()).isCloseTo(100.4, within(1e-10));
    }

    @Test
    void upToDownProducesSell() {
        var setup = warmedSetup(0);
        close(setup, 0, 94);
        close(setup, 1, 104);
        close(setup, 2, 106);
        close(setup, 3, 108);
        close(setup, 4, 110);
        var reversal = close(setup, 5, 90);
        assertThat(reversal.previousDirection()).isEqualTo(Setup91.Direction.UP);
        assertThat(reversal.direction()).isEqualTo(Setup91.Direction.DOWN);
        assertThat(reversal.candidate()).contains(Setup91.Candidate.SELL);
        assertThat(reversal.previousFiveEma9()).containsExactly(94.0, 96.0, 98.0, 100.0, 102.0);
        assertThat(reversal.previousFiveDirection()).isEqualTo(Setup91.Direction.UP);
        assertThat(reversal.previousEma9()).isEqualTo(102.0);
        assertThat(reversal.ema9()).isCloseTo(99.6, within(1e-10));
        assertThat(reversal.previousSlope()).isEqualTo(2.0);
        assertThat(reversal.currentSlope()).isCloseTo(-2.4, within(1e-10));
    }

    @ParameterizedTest
    @ValueSource(doubles = {90, 110})
    void flatToEitherDirectionIsRejected(double price) {
        var setup = warmedSetup(0);
        var seed = close(setup, 0, 100);
        assertThat(seed.ema9()).isEqualTo(100);
        assertThat(seed.previousEma9()).isNull();
        assertThat(seed.previousSlope()).isNull();
        assertThat(seed.currentSlope()).isNull();
        assertThat(seed.direction()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(seed.candidate()).isEmpty();
        assertThat(close(setup, 1, 100).direction()).isEqualTo(Setup91.Direction.FLAT);
        var observation = close(setup, 2, price);
        assertThat(observation.direction()).isNotEqualTo(Setup91.Direction.FLAT);
        assertThat(observation.candidate()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 1})
    void flatInterruptsPreviouslyEstablishedDirection(double sign) {
        var setup = warmedSetup(0);
        close(setup, 0, 100);
        close(setup, 1, 100 + sign * 10);
        assertThat(close(setup, 2, 100 + sign * 2).direction()).isEqualTo(Setup91.Direction.FLAT);
        var observation = close(setup, 3, 100 - sign * 10);
        assertThat(observation.direction()).isNotEqualTo(Setup91.Direction.FLAT);
        assertThat(observation.candidate()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 1})
    void configurableFlatThresholdIncludesBoundary(double sign) {
        var setup = warmedSetup(1.0);
        assertThat(setup.researchFlatSlopeThresholdPoints()).isEqualTo(1.0);
        assertThat(new Setup91().researchFlatSlopeThresholdPoints()).isEqualTo(1.0);
        close(setup, 0, 100);
        var boundary = close(setup, 1, 100 + sign * 5);
        assertThat(boundary.currentSlope()).isEqualTo(sign);
        assertThat(boundary.direction()).isEqualTo(Setup91.Direction.FLAT);
        var small = close(setup, 2, 100 + sign * 3);
        assertThat(small.direction()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(close(setup, 3, 100 - sign * 20).candidate()).contains(sign > 0 ? Setup91.Candidate.SELL : Setup91.Candidate.BUY);
    }

    @Test
    void rolloverUsesOnlyClosedCandleAndPreservesActualAvailability() {
        var setup = warmedSetup(0);
        var processor = new IntrabarM5Processor();
        double[] closes = {106, 96, 94, 92, 90, 110};
        for (int i = 0; i < closes.length; i++) {
            if (i == 4) setup.onEvent(processor.onEvent(tick(START + i * M5, 105)));
            setup.onEvent(processor.onEvent(tick(START + i * M5, closes[i])));
        }
        assertThat(setup.onEvent(processor.onEvent(tick(START + 5 * M5 + 1, 120)))).isEmpty();
        assertThat(setup.onEvent(processor.onEvent(tick(START + 5 * M5 + 2, 110)))).isEmpty();
        var reversal = setup.onEvent(processor.onEvent(tick(START + 6 * M5 + 7, 9999))).orElseThrow();
        assertThat(reversal.candidate()).contains(Setup91.Candidate.BUY);
        assertThat(reversal.ema9()).isCloseTo(100.4, within(1e-10));
        assertThat(reversal.previousFiveEma9()).containsExactly(106.0, 104.0, 102.0, 100.0, 98.0);
        assertThat(reversal.candle().bucketStartTimeMsc()).isEqualTo(START + 5 * M5);
        assertThat(reversal.availableAtTimeMsc()).isEqualTo(START + 6 * M5 + 7);
    }

    @Test
    void invalidInputIsRejectedBeforeChangingEma() {
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Setup91(invalid));
        }
        var setup = warmedSetup(0);
        var first = candle(0, 100);
        assertThatIllegalArgumentException().isThrownBy(() -> setup.onCandleClosed(first, START + M5 - 1));
        close(setup, 0, 100);
        assertThatIllegalArgumentException().isThrownBy(() -> close(setup, 0, 200));
        var other = new IntrabarCandleSnapshot("OTHER", START + M5, 200, 200, 200, 200);
        assertThatIllegalArgumentException().isThrownBy(() -> setup.onCandleClosed(other, START + 2 * M5));
        assertThat(close(setup, 1, 90).ema9()).isEqualTo(98);
    }

    @Test
    void ta4jWarmupCannotEstablishDirectionOrCreateCandidates() {
        var setup = new Setup91();
        for (int index = 0; index < 9; index++) {
            var observation = close(setup, index, index % 2 == 0 ? 100 : 200);
            assertThat(observation.ema9()).isNaN();
            assertThat(observation.currentSlope()).isNull();
            assertThat(observation.previousSlope()).isNull();
            assertThat(observation.direction()).isEqualTo(Setup91.Direction.FLAT);
            assertThat(observation.candidate()).isEmpty();
        }
        var seed = close(setup, 9, 100);
        assertThat(seed.ema9()).isEqualTo(100);
        assertThat(seed.currentSlope()).isNull();
        assertThat(seed.candidate()).isEmpty();
        assertThat(close(setup, 10, 90).candidate()).isEmpty();
        assertThat(close(setup, 11, 110).candidate()).contains(Setup91.Candidate.BUY);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mixedOlderEmaValuesDoNotBlockThreePointReversal(boolean buy) {
        var observation = reversalAfter(buy, 106, 104, 105, 100, 98);
        assertThat(observation.previousFiveDirection()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(observation.direction()).isEqualTo(buy ? Setup91.Direction.UP : Setup91.Direction.DOWN);
        assertThat(observation.candidate()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void equalOlderEmaValuesDoNotBlockThreePointReversal(boolean buy) {
        var observation = reversalAfter(buy, 106, 104, 104, 100, 98);
        assertThat(observation.previousFiveDirection()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(observation.candidate()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fewerThanFiveValidPreviousValuesCanQualify(boolean buy) {
        var observation = reversalAfter(buy, 104, 102, 100, 98);
        assertThat(observation.previousFiveEma9()).hasSize(5);
        assertThat(observation.previousFiveEma9().getFirst()).isNaN();
        assertThat(observation.previousFiveEma9().stream().filter(Double::isFinite).count()).isEqualTo(4);
        assertThat(observation.previousFiveDirection()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(observation.candidate()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
    }

    @Test
    void classificationUsesEmaDespiteNonMonotonicPriceCloses() {
        // Closes 106, 96, 99, 88, 90 are mixed, but EMA is strictly decreasing.
        var observation = reversalAfter(true, 106, 104, 103, 100, 98);
        assertThat(observation.previousFiveEma9()).containsExactly(106.0, 104.0, 103.0, 100.0, 98.0);
        assertThat(observation.previousFiveDirection()).isEqualTo(Setup91.Direction.DOWN);
        assertThat(observation.candidate()).contains(Setup91.Candidate.BUY);
    }

    @ParameterizedTest
    @CsvSource({"true,-2,2,true", "false,-2,2,true", "true,-4,1.001,true", "false,-4,1.001,true",
            "true,-1.001,4,true", "false,-1.001,4,true",
            "true,-1,4,true", "false,-1,4,true", "true,-4,1,true", "false,-4,1,true",
            "true,-1,2,false", "false,-1,2,false", "true,-2,1,false", "false,-2,1,false",
            "true,-0.01,2,false", "false,-0.01,2,false", "true,-2,0.01,false", "false,-2,0.01,false",
            "true,0,2,false", "false,0,2,false", "true,-2,0,false", "false,-2,0,false",
            "true,2,2,false", "false,2,2,false", "true,-2,-2,false", "false,-2,-2,false"})
    void nativeDirectionRequiresStrictSignsAndCloseConfirmation(boolean buy, double priorSlope,
                                                         double currentSlope, boolean expected) {
        var setup = new Setup91();
        for (int i = -9; i < 0; i++) close(setup, i, 100);
        close(setup, 0, 100); // First finite EMA, not yet a direction.
        double sign = buy ? 1 : -1;
        double previousEma = 100 + sign * priorSlope;
        var previous = close(setup, 1, 100 + 5 * sign * priorSlope);
        var reversal = close(setup, 2, previousEma + 5 * sign * currentSlope);
        assertThat(previous.ema9()).isCloseTo(previousEma, within(1e-10));
        assertThat(reversal.previousSlope()).isCloseTo(sign * priorSlope, within(1e-10));
        assertThat(reversal.currentSlope()).isCloseTo(sign * currentSlope, within(1e-10));
        if (expected) assertThat(reversal.candidate()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
        else assertThat(reversal.candidate()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void optionalZeroSlopeThresholdStillRequiresCloseConfirmation(boolean buy) {
        var setup = warmedSetup(0);
        close(setup, 0, 100);
        close(setup, 1, buy ? 99.95 : 100.05);
        assertThat(close(setup, 2, buy ? 100.04 : 99.96).candidate()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true,0,false", "false,0,false", "true,10,false", "false,10,false",
            "true,14.999,false", "false,14.999,false", "true,15,false", "false,15,false",
            "true,15.001,true", "false,15.001,true", "true,20,true", "false,20,true"})
    void closeConfirmationRequiresStrictlyMoreThanFifteenPoints(boolean buy, double displacement,
                                                               boolean expected) {
        var setup = warmedSetup(1);
        closeOhlc(setup, 0, buy, 130, 70, 100);
        closeOhlc(setup, 1, buy, 130, 70, 90);
        var result = closeOhlc(setup, 2, buy, 130, 70, 90 + displacement);
        // Equal highs/lows deliberately violate the removed extremes filter.
        if (displacement >= 14.999) {
            assertThat(result.reversal()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
            assertThat(result.previousDirection()).isEqualTo(buy ? Setup91.Direction.DOWN : Setup91.Direction.UP);
            assertThat(result.direction()).isEqualTo(buy ? Setup91.Direction.UP : Setup91.Direction.DOWN);
        }
        if (expected) assertThat(result.candidate()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
        else assertThat(result.candidate()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmationRejectsSmallTurnWithoutSkippingEmaOrDelayingSignal(boolean buy) {
        var setup = warmedSetup(1);
        closeOhlc(setup, 0, buy, 130, 70, 100);
        closeOhlc(setup, 1, buy, 130, 70, 90);
        var rejected = closeOhlc(setup, 2, buy, 130, 70, 105);
        assertThat(rejected.currentSlope()).isCloseTo(buy ? 1.4 : -1.4, within(1e-10));
        assertThat(rejected.candidate()).isEmpty(); // Exactly 15 points, despite a valid EMA turn.
        var continued = closeOhlc(setup, 3, buy, 140, 60, 130);
        assertThat(continued.ema9()).isCloseTo(buy ? 105.52 : 94.48, within(1e-10));
        assertThat(continued.candidate()).isEmpty(); // Confirmation cannot reuse the earlier turn.
        var opposite = closeOhlc(setup, 4, buy, 140, 60, 80);
        assertThat(opposite.candidate()).contains(buy ? Setup91.Candidate.SELL : Setup91.Candidate.BUY);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unconfirmedEarlierTurnCannotBecomeDelayedSignalOnContinuation(boolean buy) {
        var setup = warmedSetup(1);
        closeOhlc(setup, 0, buy, 110, 70, 100);
        closeOhlc(setup, 1, buy, 115, 70, 90);
        var smallTurn = closeOhlc(setup, 2, buy, 120, 70, 98.05);
        assertThat(smallTurn.currentSlope()).isCloseTo(buy ? 0.01 : -0.01, within(1e-10));
        assertThat(smallTurn.direction()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(smallTurn.candidate()).isEmpty();
        var laterStrongSlope = closeOhlc(setup, 3, buy, 125, 70, 108.01);
        assertThat(laterStrongSlope.currentSlope()).isCloseTo(buy ? 2 : -2, within(1e-10));
        assertThat(laterStrongSlope.previousDirection()).isEqualTo(Setup91.Direction.FLAT);
        assertThat(laterStrongSlope.candidate()).isEmpty(); // Both slopes must qualify at this same i.
    }

    @ParameterizedTest
    @CsvSource({"true,-20,false", "false,-20,false", "true,0,false", "false,0,false",
            "true,14.999,false", "false,14.999,false", "true,15,false", "false,15,false",
            "true,15.001,true", "false,15.001,true", "true,20,true", "false,20,true"})
    void visualReversalRequiresDirectionalCloseDisplacementOnConfirmationCandle(boolean buy,
            double displacement, boolean expected) {
        var setup = visualTrend(buy);
        double price = buy ? 300 + displacement : 300 - displacement;
        var observation = close(setup, 4, price);
        assertThat(observation.currentSlope()).matches(s -> buy ? s > 0 : s < 0);
        if (expected) assertThat(observation.confirmedReversal()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
        else assertThat(observation.confirmedReversal()).isEmpty();
        // A rejected arrow does not get moved to the next, stronger candle.
        assertThat(close(setup, 5, buy ? 360 : 240).confirmedReversal()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void visualCloseFilterRemainsActiveWhenExecutionSignalsAreDisabled(boolean buy) {
        var setup = visualTrend(buy);
        double price = buy ? 320 : 280;
        var completed = candle(4, price);
        var developing = candle(5, price);
        var state = new br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState(
                "WINFUT", START + 5 * M5, price, developing, null, null, completed);
        var observation = setup.onEvent(state, false).orElseThrow();
        assertThat(observation.candidate()).isEmpty();
        assertThat(observation.confirmedReversal()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
    }

    private static Setup91 visualTrend(boolean buy) {
        var setup = new Setup91();
        for (int i = -10; i < 0; i++) close(setup, i, buy ? 200 : 400);
        double[] prices = {200, 150, 100, 300};
        for (int i = 0; i < prices.length; i++) close(setup, i, buy ? prices[i] : 600 - prices[i]);
        return setup;
    }

    private static Setup91.Observation closeOhlc(Setup91 setup, int index, boolean buy,
                                                double high, double low, double close) {
        var candle = new IntrabarCandleSnapshot("WINFUT", START + index * M5,
                buy ? close : 200 - close, buy ? high : 200 - low,
                buy ? low : 200 - high, buy ? close : 200 - close);
        return setup.onCandleClosed(candle, START + (index + 1) * M5);
    }

    private static Setup91.Observation reversalAfter(boolean buy, double... emaValues) {
        var setup = warmedSetup(0);
        for (int i = 0; i < emaValues.length; i++) {
            // Invert EMA alpha 0.2 to construct deterministic closes for these EMA values.
            double price = i == 0 ? emaValues[i] : 5 * emaValues[i] - 4 * emaValues[i - 1];
            close(setup, i, buy ? price : 200 - price);
        }
        return close(setup, emaValues.length, buy ? 110 : 90);
    }

    private static Setup91 warmedSetup(double threshold) {
        var setup = new Setup91(threshold);
        for (int index = -9; index < 0; index++) close(setup, index, 100);
        return setup;
    }

    private static Setup91.Observation close(Setup91 setup, int index, double price) {
        return setup.onCandleClosed(candle(index, price), START + (index + 1) * M5);
    }

    private static IntrabarCandleSnapshot candle(int index, double price) {
        return new IntrabarCandleSnapshot("WINFUT", START + index * M5, price, 1000 + index, 50 - index, price);
    }

    private static CanonicalPriceEvent tick(long time, double price) {
        return new CanonicalPriceEvent("WINFUT", time, price);
    }
}
