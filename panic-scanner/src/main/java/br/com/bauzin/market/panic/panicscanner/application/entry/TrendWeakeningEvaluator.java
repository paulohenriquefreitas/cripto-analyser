package br.com.bauzin.market.panic.panicscanner.application.entry;

/** Evaluates actual trend deterioration signals. */
class TrendWeakeningEvaluator {

    boolean isWeakening(boolean ema9SlopeNegative,
                        boolean ema9CrossedDown,
                        boolean latestCloseBelowSma21,
                        boolean adxWeakening,
                        boolean latestSwingLowBelowPrevious,
                        boolean movingAveragesConverging) {
        return ema9SlopeNegative
                || ema9CrossedDown
                || latestCloseBelowSma21
                || adxWeakening
                || latestSwingLowBelowPrevious
                || movingAveragesConverging;
    }
}
