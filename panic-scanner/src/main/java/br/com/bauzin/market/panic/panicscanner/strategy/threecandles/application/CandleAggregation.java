package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import java.util.*;

public final class CandleAggregation {
    private CandleAggregation() {}
    /** Never fills missing M5 bars. Reject incomplete leading M15 groups. */
    public static List<CandleUpdate> aggregate(List<CandleUpdate> source, int minutes) {
        if (minutes == 5) return List.copyOf(source);
        if (minutes != 15) throw new IllegalArgumentException("Supported timeframes: M5, M15");
        var result = new ArrayList<CandleUpdate>();
        for (int i = 0; i < source.size();) {
            var first = source.get(i); long start = first.candle().bucketStartTimeMsc();
            if (start % 900000 != 0) { i++; continue; }
            double high = first.candle().high(), low = first.candle().low(), close = first.candle().close();
            int count = 1; boolean closed = first.closed(); long available = first.availableAtMsc();
            while (count < 3 && i+count < source.size()) {
                var next = source.get(i+count);
                if (next.candle().bucketStartTimeMsc() != start + count * 300000L) break;
                high = Math.max(high, next.candle().high()); low = Math.min(low, next.candle().low());
                close = next.candle().close(); closed &= next.closed(); available = next.availableAtMsc(); count++;
            }
            boolean complete = count == 3 && closed;
            boolean forming = !source.get(i+count-1).closed() && i+count == source.size();
            if (complete || forming) result.add(new CandleUpdate(new IntrabarCandleSnapshot(first.candle().symbol(),
                    start, first.candle().open(), high, low, close), 15, complete, available));
            i += count;
        }
        return List.copyOf(result);
    }
}
