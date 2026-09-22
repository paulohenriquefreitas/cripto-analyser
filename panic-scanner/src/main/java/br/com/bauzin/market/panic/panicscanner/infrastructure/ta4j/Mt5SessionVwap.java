package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.indicators.volume.AnchoredVWAPIndicator;
import org.ta4j.core.num.Num;

/** Official candle snapshots only; UTC date is a feed-date convention, not a B3 calendar. */
public final class Mt5SessionVwap {
    private final BarSeries series;
    private final AnchoredVWAPIndicator indicator;

    public Mt5SessionVwap(BarSeries series) {
        this.series = series;
        this.indicator = new AnchoredVWAPIndicator(series, new AbstractIndicator<Boolean>(series) {
            @Override public Boolean getValue(int index) {
                return index == series.getBeginIndex() || !session(index).equals(session(index - 1));
            }
            @Override public int getCountOfUnstableBars() { return 0; }
        });
    }

    public LocalDate session(int index) {
        return series.getBar(index).getBeginTime().atZone(ZoneOffset.UTC).toLocalDate();
    }

    public Double value(int index) {
        // The first date may start mid-session. Require an observed date boundary.
        if (indicator.getAnchorIndex(index) == series.getBeginIndex()) return null;
        // TA4J uses HLC3 and bar volume (realVolume in our converter), skips zero
        // volume, returns NaN when cumulative volume is zero or any volume is invalid.
        Num value = indicator.getValue(index);
        return Num.isNaNOrNull(value) || !Double.isFinite(value.doubleValue()) ? null : value.doubleValue();
    }
}
