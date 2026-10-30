package br.com.bauzin.market.panic.panicscanner.application.win.strategy;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import java.util.*;

/** Extracted from StructureReactionScanner: identical strict 2-left/2-right criteria. */
public final class ConfirmedPivotDetector {
    public enum Type { TOP, BOTTOM }
    public record Pivot(Type type, double price, IntrabarCandleSnapshot origin, long confirmedAtMsc) {}
    private final IntrabarCandleSnapshot[] window = new IntrabarCandleSnapshot[5];
    private int count;
    private long lastClosed = Long.MIN_VALUE;

    public List<Pivot> onCandleClosed(IntrabarCandleSnapshot candle, long availableAtMsc) {
        if (availableAtMsc < candle.bucketStartTimeMsc() + 300000L)
            throw new IllegalArgumentException("Cannot confirm using a forming M5 candle");
        if (candle.bucketStartTimeMsc() <= lastClosed) return List.of();
        lastClosed = candle.bucketStartTimeMsc();
        if (count == 5) { System.arraycopy(window, 1, window, 0, 4); count = 4; }
        window[count++] = candle;
        if (count != 5) return List.of();
        var pivot = window[2];
        boolean top = true, bottom = true;
        for (int i = 0; i < 5; i++) if (i != 2) {
            top &= pivot.high() > window[i].high();
            bottom &= pivot.low() < window[i].low();
        }
        var result = new ArrayList<Pivot>(2);
        if (top) result.add(new Pivot(Type.TOP, pivot.high(), pivot, availableAtMsc));
        if (bottom) result.add(new Pivot(Type.BOTTOM, pivot.low(), pivot, availableAtMsc));
        return List.copyOf(result);
    }
    /** Equality and wick-only penetration do not retire a level. */
    public static boolean isBroken(boolean top, double price, double close) {
        return top ? close > price : close < price;
    }
}
