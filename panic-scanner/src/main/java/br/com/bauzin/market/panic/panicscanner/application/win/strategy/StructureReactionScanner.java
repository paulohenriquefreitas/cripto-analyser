package br.com.bauzin.market.panic.panicscanner.application.win.strategy;

import java.util.List;
import java.util.Optional;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;

/** Single-writer, session-confined experimental scanner. Event time only. */
public final class StructureReactionScanner {
    private final double touchDistance, breakoutDistance;
    private final long deltaThreshold;
    private double[] prices = new double[64];
    private String[] types = new String[64];
    private long[] touchedAt = new long[64];
    private int[] approaches = new int[64];
    private boolean[] emitted = new boolean[64];
    private int size;
    private final ConfirmedPivotDetector pivots = new ConfirmedPivotDetector();
    private long lastClosed = Long.MIN_VALUE, lastTime = Long.MIN_VALUE;
    private double previousPrice = Double.NaN;

    public StructureReactionScanner() { this(15, 20, 300); }
    public StructureReactionScanner(double touchDistance, double breakoutDistance, long deltaThreshold) {
        if (!Double.isFinite(touchDistance) || touchDistance < 0
                || !Double.isFinite(breakoutDistance) || breakoutDistance <= touchDistance || deltaThreshold <= 0)
            throw new IllegalArgumentException("Invalid reaction thresholds");
        this.touchDistance = touchDistance;
        this.breakoutDistance = breakoutDistance;
        this.deltaThreshold = deltaThreshold;
    }

    /** Called only when the candle is closed and available to the replay. */
    public void onCandleClosed(IntrabarCandleSnapshot candle) {
        if (candle.bucketStartTimeMsc() <= lastClosed) return;
        lastClosed = candle.bucketStartTimeMsc();
        for (int i = size - 1; i >= 0; i--) {
            if (ConfirmedPivotDetector.isBroken(types[i].equals("PIVOT_TOP"), prices[i], candle.close())) remove(i);
        }
        for (var pivot : pivots.onCandleClosed(candle, candle.bucketStartTimeMsc() + 300000L)) {
            add(pivot.price(), pivot.type() == ConfirmedPivotDetector.Type.TOP ? "PIVOT_TOP" : "PIVOT_BOTTOM");
        }
    }

    /** Price-only compatibility API: side inferred from the approach, not a known pivot type.
     * Callers guarantee levels are confirmed. Prefer the typed candle path for replay. */
    public Optional<StructureReactionEvent> checkReaction(long currentMsc, double lastPrice,
            List<Double> activeLevels, long currentDelta500ms) {
        for (int i = size - 1; i >= 0; i--) if (!activeLevels.contains(prices[i])) remove(i);
        for (double level : activeLevels) {
            if (!Double.isFinite(level) || level <= 0) throw new IllegalArgumentException("Invalid level");
            boolean exists = false;
            for (int i = 0; i < size; i++) exists |= prices[i] == level;
            if (!exists) add(level, lastPrice <= level ? "PIVOT_TOP" : "PIVOT_BOTTOM");
        }
        return checkReaction(currentMsc, lastPrice, currentDelta500ms);
    }

    public Optional<StructureReactionEvent> checkReaction(long time, double price, long delta) {
        if (!Double.isFinite(price) || price <= 0 || time < lastTime)
            throw new IllegalArgumentException("Invalid price or regressing time");
        StructureReactionEvent result = null;
        for (int i = 0; i < size; i++) {
            if (emitted[i]) continue;
            int direction = types[i].equals("PIVOT_TOP") ? 1 : -1;
            double distance = (price - prices[i]) * direction;
            boolean strongForward = direction > 0 ? delta >= deltaThreshold : delta <= -deltaThreshold;
            boolean strongReverse = direction > 0 ? delta <= -deltaThreshold : delta >= deltaThreshold;
            String reaction = null;
            if (Double.isFinite(previousPrice) && distance > breakoutDistance && strongForward
                    && (previousPrice - prices[i]) * direction <= breakoutDistance) reaction = "BREAKOUT";
            else if (touchedAt[i] != Long.MIN_VALUE && time > touchedAt[i]
                    && approaches[i] == direction && strongReverse && distance <= touchDistance
                    && Math.abs(price - prices[i]) <= touchDistance) reaction = "TOUCH_REVERSAL";
            if (reaction != null && result == null) {
                result = new StructureReactionEvent(time, prices[i], types[i], reaction, delta);
                emitted[i] = true;
            }
            if (touchedAt[i] != Long.MIN_VALUE && time > touchedAt[i]) touchedAt[i] = Long.MIN_VALUE;
            if (Math.abs(price - prices[i]) <= touchDistance && touchedAt[i] == Long.MIN_VALUE) {
                touchedAt[i] = time;
                approaches[i] = Double.isFinite(previousPrice) && previousPrice != prices[i]
                        ? (previousPrice < prices[i] ? 1 : -1) : direction;
            }
        }
        previousPrice = price; lastTime = time;
        return Optional.ofNullable(result);
    }

    private void add(double price, String type) {
        if (size == prices.length) {
            int capacity = size * 2;
            prices = java.util.Arrays.copyOf(prices, capacity);
            types = java.util.Arrays.copyOf(types, capacity);
            touchedAt = java.util.Arrays.copyOf(touchedAt, capacity);
            approaches = java.util.Arrays.copyOf(approaches, capacity);
            emitted = java.util.Arrays.copyOf(emitted, capacity);
        }
        prices[size] = price; types[size] = type; touchedAt[size] = Long.MIN_VALUE;
        emitted[size] = false; approaches[size++] = 0;
    }
    private void remove(int index) {
        int last = --size;
        prices[index] = prices[last]; types[index] = types[last];
        touchedAt[index] = touchedAt[last]; approaches[index] = approaches[last]; emitted[index] = emitted[last];
        types[last] = null;
    }
}
