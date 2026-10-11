package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket;
import org.ta4j.core.*;
import org.ta4j.core.indicators.ATRIndicator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One hypothetical position at a time. Partial limit fills at 1R; stop uses observed LASTs. */
public final class Setup91TriggerResearch {
    public enum Mode {
        FIRST_RETURN,
        /** Legacy command alias for PARTIAL_1R_BREAKEVEN_TARGET_3_5R. */
        BREAKEVEN_1R_TARGET_2R,
        /** Legacy command alias; no ATR trailing is applied. */
        PARTIAL_1R_ATR_TRAILING,
        PARTIAL_1R_BREAKEVEN_TARGET_3_5R
    }
    public enum Exit { STOP_LOSS, RETURN_TO_ENTRY, BREAKEVEN, TARGET_3_5R, DAY_END }
    public record Result(String id, Setup91Breakout.Event trigger, double initialStop, double risk,
                         double mfe, double mae, long mfeTimeMsc, long maeTimeMsc,
                         boolean reachedOneR, Exit exit, long exitTimeMsc, long exitSequence,
                         double exitPrice, double points, Double partialPrice, Long partialSequence,
                         Double protectedStop) {}

    public enum Stage { ENTRY, PARTIAL, BREAKEVEN, EXIT, REJECTED }
    public record TradeEvent(String type, String eventId, String tradeId, Stage stage, String symbol,
                             Setup91.Candidate side, long timeMsc, long sequence, double price,
                             double entry, double initialStop, double risk, double stop,
                             Double points, Exit exit, String reason) {}
    private java.util.function.Consumer<TradeEvent> tradeListener = event -> {};
    public void setTradeListener(java.util.function.Consumer<TradeEvent> listener) {
        tradeListener = Objects.requireNonNull(listener);
    }
    private void emit(Pending item, Stage stage, HistoricalCanonicalPriceEvent tick,
                      double price, Double points, Exit exit) {
        tradeListener.accept(new TradeEvent("REPLAY_TRADE_EVENT", item.id + "-" + stage, item.id, stage,
                item.trigger.signal().candle().symbol(), item.trigger.signal().side(),
                tick.priceEvent().timeMsc(), tick.sequence(), price, item.trigger.lastPrice(),
                item.stop, item.risk, item.activeStop, points, exit, null));
    }
    private void closePosition(Pending item, Exit exit, HistoricalCanonicalPriceEvent tick) {
        var result = item.finish(exit, tick, managed());
        results.add(result);
        emit(item, Stage.EXIT, tick, result.exitPrice(), result.points(), exit);
    }

    private static final LocalTime LAST_ENTRY_TIME_UTC = LocalTime.of(17, 30);

    private final Mode mode;
    private final IntrabarM5Processor processor = new IntrabarM5Processor();
    private final BarSeries closedSeries = new BaseBarSeriesBuilder().withName("setup91-exits-closed-M5").build();
    private final ATRIndicator atr14 = new ATRIndicator(closedSeries, 14);

    /** Seed only authoritative closed M5 history, before the first LAST. */
    public void warmUp(String symbol, long start, List<IntrabarCandleSnapshot> candles) {
        if (last != null || finished) throw new IllegalStateException("Warm up before LASTs");
        processor.warmUp(symbol, start, candles);
        candles.forEach(this::addClosed);
    }

    private void addClosed(IntrabarCandleSnapshot candle) {
        var n = closedSeries.numFactory();
        var begin = Instant.ofEpochMilli(candle.bucketStartTimeMsc());
        var duration = Duration.ofMillis(M5Bucket.DURATION_MSC);
        closedSeries.addBar(new BaseBar(duration, begin, begin.plus(duration),
                n.numOf(candle.open()), n.numOf(candle.high()), n.numOf(candle.low()),
                n.numOf(candle.close()), n.zero(), n.zero(), 0L));
    }

    private double currentAtr() {
        return closedSeries.getEndIndex() < atr14.getCountOfUnstableBars() ? Double.NaN
                : atr14.getValue(closedSeries.getEndIndex()).doubleValue();
    }

    public static double roundToWinTick(double price) {
        if (!Double.isFinite(price)) throw new IllegalArgumentException("Finite price required");
        return Math.round(price / 5.0) * 5.0;
    }

    private boolean managed() { return mode != Mode.FIRST_RETURN; }
    private final List<Pending> pending = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private HistoricalCanonicalPriceEvent last;
    private boolean finished;

    public Setup91TriggerResearch(Mode mode) { this.mode = Objects.requireNonNull(mode); }
    public boolean hasOpenPosition() { return !pending.isEmpty(); }

    /** Call once per original LAST, BEFORE registering triggers produced by that LAST. */
    public void onLast(HistoricalCanonicalPriceEvent historical) {
        Objects.requireNonNull(historical);
        if (finished) throw new IllegalStateException("Research is finished");
        if (last != null && (historical.sequence() <= last.sequence()
                || historical.priceEvent().timeMsc() < last.priceEvent().timeMsc()
                || !historical.priceEvent().symbol().equals(last.priceEvent().symbol()))) {
            throw new IllegalArgumentException("Expected ordered same-symbol LASTs");
        }
        var state = processor.onEvent(historical.priceEvent());
        if (state.completedCandle() != null) addClosed(state.completedCandle());
        last = historical;
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            double movement = item.signedMovement(historical.priceEvent().price());
            if (movement > item.mfe) { item.mfe = movement; item.mfeTime = historical.priceEvent().timeMsc(); }
            if (-movement > item.mae) { item.mae = -movement; item.maeTime = historical.priceEvent().timeMsc(); }
            item.reachedOneR |= movement >= item.risk;
            Exit exit = null;
            // Stops fill at observed LAST; limit targets fill at their fixed level.
            if (managed() && item.partialPrice != null
                    && movement <= 0) {
                exit = Exit.BREAKEVEN;
            } else if (mode == Mode.FIRST_RETURN && item.mfe > 0 && movement <= 0) {
                exit = Exit.RETURN_TO_ENTRY;
            } else if (movement <= -item.risk) {
                exit = Exit.STOP_LOSS;
            }
            if (exit != null) {
                closePosition(item, exit, historical);
                iterator.remove();
            } else if (managed() && item.reachedOneR) {
                if (item.partialPrice == null) {
                    item.partialPrice = item.trigger.lastPrice() + item.sign * item.risk;
                    item.partialSequence = historical.sequence();
                    item.activeStop = item.trigger.lastPrice();
                    emit(item, Stage.PARTIAL, historical, item.partialPrice, 0.5 * item.risk, null);
                    emit(item, Stage.BREAKEVEN, historical, item.activeStop, null, null);
                }
                if (movement >= 3.5 * item.risk) {
                    closePosition(item, Exit.TARGET_3_5R, historical);
                    iterator.remove();
                }
            }
        }
    }

    public void onTrigger(Setup91Breakout.Event trigger) {
        if (finished || last == null) throw new IllegalStateException("Supply the triggering LAST first");
        if (hasOpenPosition()) return;
        if (trigger.type() != Setup91Breakout.EventType.SETUP91_BUY_TRIGGERED
                && trigger.type() != Setup91Breakout.EventType.SETUP91_SELL_TRIGGERED) {
            throw new IllegalArgumentException("Expected a TRIGGERED event");
        }
        if (trigger.sequence() != last.sequence() || trigger.timeMsc() != last.priceEvent().timeMsc()
                || trigger.lastPrice() != last.priceEvent().price()
                || !trigger.signal().candle().symbol().equals(last.priceEvent().symbol())) {
            throw new IllegalArgumentException("Trigger must belong to the current LAST");
        }
        String id = trigger.type() + "-" + trigger.signal().candle().bucketStartTimeMsc() + "-" + trigger.sequence();
        if (pending.stream().anyMatch(p -> p.id.equals(id)) || results.stream().anyMatch(r -> r.id().equals(id))) {
            throw new IllegalArgumentException("Duplicate trigger");
        }
        var item = new Pending(id, trigger);
        // FIRST_RETURN remains a raw diagnostic; both managed mode names use the new rules.
        if (managed()) {
            var triggerTimeUtc = Instant.ofEpochMilli(trigger.timeMsc()).atZone(ZoneOffset.UTC).toLocalTime();
            if (triggerTimeUtc.isAfter(LAST_ENTRY_TIME_UTC)) { reject(item, "Horario depois de 17:30 UTC"); return; }
            double atr = currentAtr();
            if (!Double.isFinite(atr) || atr <= 0) { reject(item, "ATR14 sem aquecimento"); return; }
            double floor = Math.max(130.0, 1.0 * atr), ceiling = Math.min(550.0, 4.0 * atr);
            if (item.risk < floor || item.risk > ceiling) {
                reject(item, String.format(java.util.Locale.ROOT, "R %.0f fora de %.0f a %.0f pts", item.risk, floor, ceiling));
                return;
            }
        }
        pending.add(item);
        emit(item, Stage.ENTRY, last, trigger.lastPrice(), null, null);
    }

    private void reject(Pending item, String reason) {
        tradeListener.accept(new TradeEvent("REPLAY_TRADE_EVENT", item.id + "-REJECTED", item.id,
                Stage.REJECTED, item.trigger.signal().candle().symbol(), item.trigger.signal().side(),
                last.priceEvent().timeMsc(), last.sequence(), item.trigger.lastPrice(), item.trigger.lastPrice(),
                item.stop, item.risk, item.activeStop, null, null, reason));
    }

    public List<Result> completedResults() { return List.copyOf(results); }

    /** EOF is a separate outcome; it is not a target, a stop, or a fabricated tick. */
    public List<Result> finishDay() {
        if (!finished) {
            for (var item : pending) closePosition(item, Exit.DAY_END, last);
            pending.clear();
            finished = true;
        }
        return results.stream().sorted(java.util.Comparator.comparingLong(r -> r.trigger().sequence())).toList();
    }

    private static final class Pending {
        final String id;
        final Setup91Breakout.Event trigger;
        final double sign, stop, risk;
        double mfe, mae;
        long mfeTime, maeTime;
        boolean reachedOneR;
        Double partialPrice;
        Long partialSequence;
        double activeStop;

        Pending(String id, Setup91Breakout.Event trigger) {
            this.id = id;
            this.trigger = trigger;
            boolean buy = trigger.signal().side() == Setup91.Candidate.BUY;
            sign = buy ? 1 : -1;
            stop = buy ? trigger.signal().candle().low() : trigger.signal().candle().high();
            risk = sign * (trigger.lastPrice() - stop);
            if (!Double.isFinite(risk) || risk <= 0) throw new IllegalArgumentException("Invalid initial stop risk");
            activeStop = stop;
            mfeTime = maeTime = trigger.timeMsc();
        }

        double signedMovement(double price) { return sign * (price - trigger.lastPrice()); }

        Result finish(Exit exit, HistoricalCanonicalPriceEvent last, boolean managed) {
            double fill = exit == Exit.TARGET_3_5R ? trigger.lastPrice() + sign * 3.5 * risk
                    : managed ? roundToWinTick(last.priceEvent().price()) : last.priceEvent().price();
            return new Result(id, trigger, stop, risk, mfe, mae, mfeTime, maeTime, reachedOneR,
                    exit, last.priceEvent().timeMsc(), last.sequence(), fill,
                    partialPrice == null ? signedMovement(fill)
                            : 0.5 * signedMovement(partialPrice) + 0.5 * signedMovement(fill),
                    partialPrice, partialSequence, partialPrice == null ? null : activeStop);
        }
    }
}
