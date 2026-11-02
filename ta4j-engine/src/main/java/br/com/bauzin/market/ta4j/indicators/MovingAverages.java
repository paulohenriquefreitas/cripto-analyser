package br.com.bauzin.market.ta4j.indicators;

import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;

/** TA4J moving averages sharing the caller's series, number factory and cache lifecycle.
 * No rounding, warmup filtering, temporal conversion or application policy is applied.
 */
public final class MovingAverages {
    private MovingAverages() {}

    public static SMAIndicator sma(Indicator<Num> source, int period) {
        return new SMAIndicator(source, period);
    }

    public static EMAIndicator ema(Indicator<Num> source, int period) {
        return new EMAIndicator(source, period);
    }
}
