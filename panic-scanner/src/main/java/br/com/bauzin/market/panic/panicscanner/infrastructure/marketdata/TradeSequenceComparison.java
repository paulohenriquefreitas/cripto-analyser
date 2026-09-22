package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.util.List;

public record TradeSequenceComparison(
        long exactMatches,
        long missing,
        long extra,
        long different,
        long orderMismatch,
        long timestampMismatch,
        long priceMismatch,
        long volumeMismatch,
        long sideMismatch,
        Difference firstDifference,
        List<TradeValidationEvent> live,
        List<TradeValidationEvent> historical) {

    public enum DifferenceKind { MISSING_LIVE, EXTRA_LIVE, DIFFERENT, ORDER }

    public record Difference(DifferenceKind kind, int liveIndex, int historicalIndex) {}

    public double matchPercentage() {
        long denominator = Math.max(live.size(), historical.size());
        return denominator == 0 ? 100.0 : exactMatches * 100.0 / denominator;
    }

    public boolean exact() {
        return missing == 0 && extra == 0 && different == 0 && orderMismatch == 0
                && live.size() == historical.size() && exactMatches == live.size();
    }
}
