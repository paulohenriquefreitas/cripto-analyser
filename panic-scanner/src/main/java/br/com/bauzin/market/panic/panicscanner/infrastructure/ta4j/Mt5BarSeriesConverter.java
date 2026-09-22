package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

public final class Mt5BarSeriesConverter {
    private Mt5BarSeriesConverter() {}

    public static BarSeries convert(List<Mt5Candle> candles) {
        BarSeries series = new BaseBarSeriesBuilder().withName("mt5-WINV26-M5").build();
        series.setMaximumBarCount(1000);
        var numbers = series.numFactory();
        Duration period = Duration.ofMinutes(5);
        for (Mt5Candle candle : candles) {
            Instant begin = Instant.ofEpochSecond(candle.time());
            // MT5 time is bar OPEN time; TA4J receives explicit begin and end.
            series.addBar(new BaseBar(period, begin, begin.plus(period),
                    numbers.numOf(candle.open()), numbers.numOf(candle.high()),
                    numbers.numOf(candle.low()), numbers.numOf(candle.close()),
                    numbers.numOf(candle.realVolume()), numbers.zero(), 0L));
        }
        return series;
    }
}
