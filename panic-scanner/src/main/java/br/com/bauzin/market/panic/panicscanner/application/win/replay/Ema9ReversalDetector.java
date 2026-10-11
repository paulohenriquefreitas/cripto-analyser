package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
import java.util.Optional;

/** Two closed M5 slopes confirm the visual EMA direction; intrabar prices are never used. */
final class Ema9ReversalDetector {
    // WIN points measured on the EMA itself over two candles. Initial visual calibration.
    static final double DEFAULT_MIN_DISPLACEMENT_POINTS = 15.0;
    private static final int LATERAL_WINDOWS_TO_RESET = 3;
    private static final int GRADUAL_TURN_GRACE_CANDLES = 2;
    private final double minDisplacementPoints;
    private Long previousTime;
    private Double previousValue;
    private Double previousSlope;
    private Setup91.Direction established = Setup91.Direction.FLAT;
    private int lateralWindows;
    private int oppositeSlopes;
    private double oppositeDisplacement;
    private GradualTurn gradualTurn;

    private record GradualTurn(Setup91.Direction origin, double displacement, int candlesRemaining) {}

    Ema9ReversalDetector() { this(DEFAULT_MIN_DISPLACEMENT_POINTS); }

    Ema9ReversalDetector(double minDisplacementPoints) {
        if (!Double.isFinite(minDisplacementPoints) || minDisplacementPoints < 0) {
            throw new IllegalArgumentException("minDisplacementPoints must be finite and nonnegative");
        }
        this.minDisplacementPoints = minDisplacementPoints;
    }

    Optional<Setup91.Candidate> onClosedEma(long candleTimeMsc, double value) {
        if (!Double.isFinite(value) || previousTime == null
                || candleTimeMsc - previousTime != M5Bucket.DURATION_MSC) {
            established = Setup91.Direction.FLAT;
            lateralWindows = 0;
            previousSlope = null;
            oppositeSlopes = 0;
            oppositeDisplacement = 0;
            gradualTurn = null;
            previousValue = Double.isFinite(value) ? value : null;
            previousTime = candleTimeMsc;
            return Optional.empty();
        }
        Double slope = previousValue == null ? null : value - previousValue;
        var reversalOrigin = established;
        if (gradualTurn != null) {
            double displacement = gradualTurn.displacement() + (slope == null ? 0 : Math.abs(slope));
            if (isOpposite(slope, gradualTurn.origin()) && displacement > minDisplacementPoints) {
                // This reference expires even if the slow move continues indefinitely.
                reversalOrigin = gradualTurn.origin();
                gradualTurn = gradualTurn.candlesRemaining() > 1
                        ? new GradualTurn(gradualTurn.origin(), displacement, gradualTurn.candlesRemaining() - 1)
                        : null;
            } else gradualTurn = null;
        }
        if (isOpposite(slope, established)) {
            oppositeSlopes++;
            oppositeDisplacement += Math.abs(slope);
        } else {
            oppositeSlopes = 0;
            oppositeDisplacement = 0;
        }
        var direction = Setup91.Direction.FLAT;
        if (slope != null && previousSlope != null
                && Math.abs(slope + previousSlope) > minDisplacementPoints) {
            if (slope > 0 && previousSlope > 0) direction = Setup91.Direction.UP;
            if (slope < 0 && previousSlope < 0) direction = Setup91.Direction.DOWN;
        }
        Optional<Setup91.Candidate> reversal = Optional.empty();
        if (previousSlope != null) {
            if (direction == Setup91.Direction.FLAT) {
                // Preserve the trend through one contrary slope; forget it after consolidation.
                if (++lateralWindows >= LATERAL_WINDOWS_TO_RESET) {
                    // Keep the existing lateral reset. Only a continuous contrary move
                    // gets a short-lived reference for a later, fully confirmed reversal.
                    if (established != Setup91.Direction.FLAT && oppositeSlopes >= LATERAL_WINDOWS_TO_RESET) {
                        gradualTurn = new GradualTurn(established, oppositeDisplacement, GRADUAL_TURN_GRACE_CANDLES);
                    }
                    established = Setup91.Direction.FLAT;
                }
            } else {
                if (reversalOrigin != Setup91.Direction.FLAT && reversalOrigin != direction) {
                    reversal = Optional.of(direction == Setup91.Direction.UP
                            ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
                }
                established = direction;
                lateralWindows = 0;
                gradualTurn = null;
                oppositeSlopes = 0;
                oppositeDisplacement = 0;
            }
        }
        previousSlope = slope;
        previousValue = value;
        previousTime = candleTimeMsc;
        return reversal;
    }

    private static boolean isOpposite(Double slope, Setup91.Direction origin) {
        return slope != null && ((origin == Setup91.Direction.UP && slope < 0)
                || (origin == Setup91.Direction.DOWN && slope > 0));
    }
}
