package br.com.bauzin.market.panic.panicscanner.domain.entry;

/** Explainable entry-score components for the selected setup type. */
public record EntryScoreBreakdown(
        int trend,
        int pullbackDepth,
        int averageTouch,
        int sma21Preserved,
        int rsi,
        int confirmationCandle,
        int higherLow,
        int volume,
        int distance,
        int breakout,
        int relativeVolume,
        int adx,
        int slopes,
        int recentReturn,
        int penalties,
        int total) {
}
