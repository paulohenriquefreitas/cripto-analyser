package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ema9ReversalDetectorTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void establishedTrendReversesOnlyOnSecondOppositeClosedSlope(boolean down) {
        var detector = new Ema9ReversalDetector();
        double[] values = {100, 130, 150, 146, 120, 90, 60};
        for (int i = 0; i < values.length; i++) {
            var result = detector.onClosedEma(i * 300_000L, down ? values[i] : 200 - values[i]);
            if (i == 4) assertThat(result).contains(down ? Setup91.Candidate.SELL : Setup91.Candidate.BUY);
            else assertThat(result).as("candle %s", i).isEmpty();
        }
    }

    @Test
    void september14IsolatedDipDoesNotReverseOrGenerateCounterArrow() {
        double[] values = {187126.840320667, 187140.4722565336, 187153.3778052269,
                187159.7022441815, 187228.7617953452, 187220.0094362762,
                187248.007549021, 187272.4060392168};
        assertThat(feed(new Ema9ReversalDetector(), values)).allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void twoTinyDeclinesAndChoppyConsolidationAreNotReversals() {
        double[] values = {100, 130, 160, 159, 158, 160, 158, 159, 157, 158, 157};
        assertThat(feed(new Ema9ReversalDetector(), values)).allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void leavingProlongedConsolidationEstablishesFreshDirection() {
        double[] values = {100, 130, 160, 159, 160, 159, 158, 130, 100, 120, 150};
        var results = feed(new Ema9ReversalDetector(), values);
        for (int i = 0; i < results.size() - 1; i++) assertThat(results.get(i)).as("candle %s", i).isEmpty();
        assertThat(results.getLast()).contains(Setup91.Candidate.BUY);
    }

    @Test
    void displacementMustExceedConfiguredBoundary() {
        double[] values = {100, 130, 160, 150, 145, 144};
        var results = feed(new Ema9ReversalDetector(15), values);
        assertThat(results).allSatisfy(r -> assertThat(r).isEmpty());
        assertThat(feed(new Ema9ReversalDetector(14.99), values).get(4)).contains(Setup91.Candidate.SELL);
    }

    @Test
    void flatSlopeBreaksTwoCandleConfirmation() {
        assertThat(feed(new Ema9ReversalDetector(), new double[]{100, 130, 160, 140, 140, 120}))
                .allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void unavailableValuesAndMissingCandlesResetPriorTrend() {
        for (boolean gap : new boolean[]{true, false}) {
            var detector = new Ema9ReversalDetector();
            feed(detector, new double[]{100, 130, 160});
            assertThat(detector.onClosedEma(900_000, gap ? 160 : Double.NaN)).isEmpty();
            long start = gap ? 1_500_000 : 1_200_000;
            assertThat(detector.onClosedEma(start, 140)).isEmpty();
            assertThat(detector.onClosedEma(start + 300_000, 120)).isEmpty();
            assertThat(detector.onClosedEma(start + 600_000, 100)).isEmpty();
        }
    }

    @Test
    void invalidConfigurationIsRejected() {
        for (double value : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> new Ema9ReversalDetector(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void august13GradualTurnKeepsOriginOnlyUntilStrongTwoCandleConfirmation(boolean up) {
        double[] values = {169944.481801412, 169885.5854411296, 169843.4683529037,
                169849.774682323, 169855.8197458584, 169857.6557966867,
                169869.1246373494, 169906.2997098795, 169952.0397679036};
        if (!up) for (int i = 0; i < values.length; i++) values[i] = 340000 - values[i];
        var results = feed(new Ema9ReversalDetector(), values);
        for (int i = 0; i < results.size(); i++) {
            if (i == 7) assertThat(results.get(i)).contains(up ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
            else assertThat(results.get(i)).as("candle %s", i).isEmpty();
        }
    }

    @Test
    void manySmallMonotonicCandlesCannotKeepOldTrendAlive() {
        assertThat(feed(new Ema9ReversalDetector(), new double[]{100, 130, 160,
                159, 158, 157, 156, 155, 154, 153, 123, 93}))
                .allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void gradualTurnGraceExpiresEvenWithContinuedProgress() {
        assertThat(feed(new Ema9ReversalDetector(), new double[]{100, 130, 160,
                156, 152, 148, 144, 140, 110, 80}))
                .allSatisfy(r -> assertThat(r).isEmpty());
    }

    @Test
    void stoppedOrAlternatingMovementDiscardsGradualTurnReference() {
        for (double interruption : new double[]{148, 149}) {
            assertThat(feed(new Ema9ReversalDetector(), new double[]{100, 130, 160,
                    156, 152, 148, interruption, 120, 90}))
                    .allSatisfy(r -> assertThat(r).isEmpty());
        }
    }

    @Test
    void firstGraceCandleMustExceedAccumulatedDisplacementBoundary() {
        var values = new double[]{100, 130, 160, 157, 154, 151, 145, 115};
        assertThat(feed(new Ema9ReversalDetector(15), values)).allSatisfy(r -> assertThat(r).isEmpty());
        assertThat(feed(new Ema9ReversalDetector(14.99), values).getLast()).contains(Setup91.Candidate.SELL);
    }

    @Test
    void pendingGradualTurnDoesNotSurviveGapOrUnavailableEma() {
        for (boolean gap : new boolean[]{true, false}) {
            var detector = new Ema9ReversalDetector();
            feed(detector, new double[]{100, 130, 160, 156, 152, 148});
            long start = gap ? 2_100_000 : 1_800_000;
            assertThat(detector.onClosedEma(start, gap ? 120 : Double.NaN)).isEmpty();
            assertThat(detector.onClosedEma(start + 300_000, 100)).isEmpty();
            assertThat(detector.onClosedEma(start + 600_000, 80)).isEmpty();
            assertThat(detector.onClosedEma(start + 900_000, 60)).isEmpty();
        }
    }

    private static ArrayList<Optional<Setup91.Candidate>> feed(Ema9ReversalDetector detector, double[] values) {
        var results = new ArrayList<Optional<Setup91.Candidate>>();
        for (int i = 0; i < values.length; i++) results.add(detector.onClosedEma(i * 300_000L, values[i]));
        return results;
    }
}
