package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain;

/** Region is the origin candle range, not an inferred valid strategy swing. */
public record StructuralLevel(String id, Type type, double price, long extremeTimeMsc,
                              long confirmedAtMsc, Region region, String relevance, State state, Long brokenAtMsc,
                              int timeframeMinutes) {
    public enum State { CONFIRMED, BROKEN }
    public StructuralLevel(String id, Type type, double price, long extremeTimeMsc,
                           long confirmedAtMsc, Region region, String relevance) {
        this(id, type, price, extremeTimeMsc, confirmedAtMsc, region, relevance, State.CONFIRMED, null, 5);
    }
    public enum Type { TOP, BOTTOM }
    public record Region(double high, double low, double midpoint, String basis) {}
}
