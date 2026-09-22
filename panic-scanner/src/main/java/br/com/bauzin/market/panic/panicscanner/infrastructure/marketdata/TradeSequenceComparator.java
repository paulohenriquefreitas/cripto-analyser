package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.util.List;

/** Bounded-lookahead ordered diff. It preserves multiplicity and never hashes into a Set. */
public final class TradeSequenceComparator {
    private static final int DEFAULT_LOOKAHEAD = 128;

    private TradeSequenceComparator() {}

    public static TradeSequenceComparison compare(
            List<TradeValidationEvent> live, List<TradeValidationEvent> historical) {
        return compare(live, historical, DEFAULT_LOOKAHEAD);
    }

    static TradeSequenceComparison compare(
            List<TradeValidationEvent> live, List<TradeValidationEvent> historical, int lookahead) {
        int i = 0, j = 0;
        long exact = 0, missing = 0, extra = 0, different = 0, order = 0;
        long timestamp = 0, price = 0, volume = 0, side = 0;
        TradeSequenceComparison.Difference first = null;
        while (i < live.size() && j < historical.size()) {
            TradeValidationEvent left = live.get(i), right = historical.get(j);
            if (left.sameTrade(right)) { exact++; i++; j++; continue; }

            int historicalMatch = find(historical, j + 1, left, lookahead);
            int liveMatch = find(live, i + 1, right, lookahead);
            if (historicalMatch >= 0 && liveMatch >= 0
                    && historicalMatch - j == liveMatch - i) {
                int block = historicalMatch - j;
                if (swappedBlocks(live, i, historical, j, block)) {
                    if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.ORDER, i, j);
                    order += block * 2L;
                    i += block * 2;
                    j += block * 2;
                    continue;
                }
            }
            if (historicalMatch >= 0 && (liveMatch < 0 || historicalMatch - j < liveMatch - i)) {
                if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.MISSING_LIVE, i, j);
                missing += historicalMatch - j;
                j = historicalMatch;
                continue;
            }
            if (liveMatch >= 0) {
                if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.EXTRA_LIVE, i, j);
                extra += liveMatch - i;
                i = liveMatch;
                continue;
            }
            if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.DIFFERENT, i, j);
            different++;
            if (left.timeMsc() != right.timeMsc()) timestamp++;
            if (Double.doubleToLongBits(left.price()) != Double.doubleToLongBits(right.price())) price++;
            if (Double.doubleToLongBits(left.volume()) != Double.doubleToLongBits(right.volume())) volume++;
            if (left.side() != right.side()) side++;
            i++; j++;
        }
        if (i < live.size()) {
            if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.EXTRA_LIVE, i, j);
            extra += live.size() - i;
        }
        if (j < historical.size()) {
            if (first == null) first = difference(TradeSequenceComparison.DifferenceKind.MISSING_LIVE, i, j);
            missing += historical.size() - j;
        }
        return new TradeSequenceComparison(exact, missing, extra, different, order,
                timestamp, price, volume, side, first, List.copyOf(live), List.copyOf(historical));
    }

    private static int find(List<TradeValidationEvent> values, int start,
                            TradeValidationEvent sought, int lookahead) {
        int end = Math.min(values.size(), start + lookahead);
        for (int index = start; index < end; index++) {
            if (values.get(index).sameTrade(sought)) return index;
        }
        return -1;
    }

    private static boolean swappedBlocks(List<TradeValidationEvent> live, int liveStart,
                                         List<TradeValidationEvent> historical, int historyStart,
                                         int size) {
        if (liveStart + size * 2 > live.size() || historyStart + size * 2 > historical.size()) return false;
        for (int offset = 0; offset < size; offset++) {
            if (!live.get(liveStart + offset).sameTrade(historical.get(historyStart + size + offset))
                    || !historical.get(historyStart + offset).sameTrade(live.get(liveStart + size + offset))) {
                return false;
            }
        }
        return true;
    }

    private static TradeSequenceComparison.Difference difference(
            TradeSequenceComparison.DifferenceKind kind, int live, int historical) {
        return new TradeSequenceComparison.Difference(kind, live, historical);
    }
}
