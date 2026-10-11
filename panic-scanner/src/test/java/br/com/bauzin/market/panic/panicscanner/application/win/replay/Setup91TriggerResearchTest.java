package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.application.win.replay.Setup91TriggerResearch.*;

class Setup91TriggerResearchTest {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void firstReturnMeasuresOnlyUntilReturnAndNeverUsesLaterExtremes(boolean buy) {
        var tracker = start(Mode.FIRST_RETURN, buy);
        tracker.onLast(last(buy, 2, 115));
        tracker.onLast(last(buy, 3, 119));
        tracker.onLast(last(buy, 4, 110));
        tracker.onLast(last(buy, 5, 180));
        var r = tracker.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.RETURN_TO_ENTRY);
        assertThat(r.mfe()).isEqualTo(9);
        assertThat(r.mae()).isZero();
        assertThat(r.points()).isZero();
        assertThat(r.exitSequence()).isEqualTo(4);
        assertThat(r.risk()).isEqualTo(20);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void exactStopTouchClosesWithoutAnyFavorableMovement(boolean buy) {
        var tracker = start(Mode.FIRST_RETURN, buy);
        tracker.onLast(last(buy, 2, 108));
        tracker.onLast(last(buy, 3, 90));
        tracker.onLast(last(buy, 4, 150));
        var r = tracker.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.STOP_LOSS);
        assertThat(r.mfe()).isZero();
        assertThat(r.mae()).isEqualTo(20);
        assertThat(r.points()).isEqualTo(-20);
        assertThat(r.initialStop()).isEqualTo(buy ? 90 : 110);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void partialAtOneRThenFixedTargetFillsExactlyAtThreeAndHalfR(boolean buy) {
        var tracker = managed(buy, 200);
        tracker.onLast(managedLast(buy, 2, 1450));
        tracker.onLast(managedLast(buy, 3, 1895));
        assertThat(tracker.hasOpenPosition()).isTrue();
        tracker.onLast(managedLast(buy, 4, 1940));
        var r = tracker.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.TARGET_3_5R);
        assertThat(r.partialPrice()).isEqualTo(buy ? 1400.0 : 1600.0);
        assertThat(r.partialSequence()).isEqualTo(2);
        assertThat(r.protectedStop()).isEqualTo(buy ? 1200.0 : 1800.0);
        assertThat(r.exitPrice()).isEqualTo(buy ? 1900.0 : 1100.0);
        assertThat(r.points()).isEqualTo(450);
        assertThat(r.exitSequence()).isEqualTo(4);
        assertThat(tracker.hasOpenPosition()).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void gapAcrossBothTargetsExecutesPartialThenFinalOnSameLast(boolean buy) {
        var events = new java.util.ArrayList<TradeEvent>();
        var tracker = managed(buy, 130);
        tracker.setTradeListener(events::add);
        tracker.onLast(managedLast(buy, 2, 1800));
        assertThat(events).extracting(TradeEvent::stage)
                .containsExactly(Stage.PARTIAL, Stage.BREAKEVEN, Stage.EXIT);
        var r = tracker.completedResults().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.TARGET_3_5R);
        assertThat(r.exitPrice()).isEqualTo(buy ? 1655.0 : 1345.0);
        assertThat(r.points()).isEqualTo(292.5);
        tracker.onLast(managedLast(buy, 3, 1000));
        assertThat(tracker.finishDay()).containsExactly(r);
    }

    @Test void atrFloorOverridesMinimumPointsAndAcceptsExactBoundary() {
        assertThat(managed(true, 195, 200).finishDay()).isEmpty();
        assertThat(managed(false, 195, 200).finishDay()).isEmpty();
        assertThat(managed(true, 200, 200).finishDay()).hasSize(1);
        assertThat(managed(false, 200, 200).finishDay()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void dynamicCeilingUsesClosedAtrAndSafetyCapForBothManagedModes(boolean buy) {
        for (var mode : List.of(Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R, Mode.PARTIAL_1R_ATR_TRAILING, Mode.BREAKEVEN_1R_TARGET_2R)) {
            assertThat(managed(mode, buy, 400, 100).finishDay()).hasSize(1);
            assertThat(managed(mode, buy, 405, 100).finishDay()).isEmpty();
            assertThat(managed(mode, buy, 550, 200).finishDay()).hasSize(1);
            assertThat(managed(mode, buy, 555, 200).finishDay()).isEmpty();
            assertThat(managed(mode, buy, 550, 550).finishDay()).hasSize(1);
            assertThat(managed(mode, buy, 550, 555).finishDay()).isEmpty();
            assertThat(managed(mode, buy, 130, 30).finishDay()).isEmpty();
            assertThat(managed(mode, buy, 130, 32.5).finishDay()).hasSize(1);
        }
        assertThat(managed(Mode.FIRST_RETURN, buy, 820, 100).finishDay()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void entryWindowUsesActualTriggerUtcWithInclusiveBoundary(boolean buy) {
        for (var mode : List.of(Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R, Mode.PARTIAL_1R_ATR_TRAILING, Mode.BREAKEVEN_1R_TARGET_2R)) {
            for (var time : List.of("2026-09-01T17:29:59.999Z", "2026-09-01T17:30:00Z")) {
                var tracker = managedAt(mode, buy, 200, 100, java.time.Instant.parse(time).toEpochMilli());
                tracker.onLast(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent("WINV26",
                        java.time.Instant.parse("2026-09-01T17:40:09Z").toEpochMilli(), buy ? 1000 : 2000), 1, 1336, 2));
                assertThat(tracker.finishDay()).hasSize(1).allSatisfy(r ->
                        assertThat(r.exit()).isEqualTo(Exit.STOP_LOSS));
            }
            for (var time : List.of("2026-09-01T17:30:00.001Z", "2026-09-01T17:40:09Z",
                    "2026-09-01T23:59:59.999Z")) {
                assertThat(managedAt(mode, buy, 200, 100,
                        java.time.Instant.parse(time).toEpochMilli()).finishDay()).isEmpty();
            }
        }
        assertThat(managedAt(Mode.FIRST_RETURN, buy, 200, 100,
                java.time.Instant.parse("2026-09-01T17:40:09Z").toEpochMilli()).finishDay()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void atrChangesAndRetracementDoNotTrailProtectedStop(boolean buy) {
        var tracker = managed(buy, 240, 200);
        tracker.onLast(managedLast(buy, 2, 1800));
        tracker.onLast(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent(
                "WINV26", 4_800_000, 1500), 1, 1336, 3));
        tracker.onLast(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent(
                "WINV26", 5_100_000, buy ? 1420 : 1580), 1, 1336, 4));
        assertThat(tracker.hasOpenPosition()).isTrue();
        var r = tracker.finishDay().getFirst();
        assertThat(r.protectedStop()).isEqualTo(buy ? 1200.0 : 1800.0);
        assertThat(r.exit()).isEqualTo(Exit.DAY_END);
        assertThat(r.points()).isEqualTo(230);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void partialImmediatelyProtectsRemainingHalfAtEntry(boolean buy) {
        var tracker = managed(buy, 240, 200);
        tracker.onLast(managedLast(buy, 2, 1440));
        tracker.onLast(managedLast(buy, 3, 1200));
        var r = tracker.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.BREAKEVEN);
        assertThat(r.protectedStop()).isEqualTo(buy ? 1200.0 : 1800.0);
        assertThat(r.points()).isEqualTo(120);
        assertThat(tracker.hasOpenPosition()).isFalse();
    }

    @Test void riskFloorRejectsNoiseAndMissingAtr() {
        assertThat(managed(true, 105).finishDay()).isEmpty();
        assertThat(managed(true, 125).finishDay()).isEmpty();
        assertThat(managed(true, 130).finishDay()).hasSize(1);
        assertThat(start(Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R, true).finishDay()).isEmpty();
        assertThat(roundToWinTick(1792.3)).isEqualTo(1790);
        assertThat(roundToWinTick(1793)).isEqualTo(1795);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void exactFinalTargetTouchFillsOnceAndCancelsRemainingStop(boolean buy) {
        var tracker = managed(buy, 200);
        tracker.onLast(managedLast(buy, 2, 1400));
        tracker.onLast(managedLast(buy, 3, 1900));
        tracker.onLast(managedLast(buy, 4, 990));
        assertThat(tracker.finishDay()).hasSize(1).allSatisfy(r -> {
            assertThat(r.exit()).isEqualTo(Exit.TARGET_3_5R);
            assertThat(r.exitSequence()).isEqualTo(3);
            assertThat(r.points()).isEqualTo(450);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void beforePartialInitialStopClosesEntirePosition(boolean buy) {
        var tracker = managed(buy, 200);
        tracker.onLast(managedLast(buy, 2, 1395));
        tracker.onLast(managedLast(buy, 3, 1000));
        assertThat(tracker.finishDay()).hasSize(1).allSatisfy(r -> {
            assertThat(r.exit()).isEqualTo(Exit.STOP_LOSS);
            assertThat(r.partialPrice()).isNull();
            assertThat(r.protectedStop()).isNull();
            assertThat(r.points()).isEqualTo(-200);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void dayEndWeightsRemainingHalfAndStopGapUsesObservedTick(boolean buy) {
        var tracker = managed(buy, 200);
        tracker.onLast(managedLast(buy, 2, 1800));
        assertThat(tracker.finishDay().getFirst().points()).isEqualTo(400);
        var gap = managed(buy, 200);
        gap.onLast(managedLast(buy, 2, 1400));
        gap.onLast(managedLast(buy, 3, 990));
        var r = gap.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.BREAKEVEN);
        assertThat(r.points()).isEqualTo(-5);
    }

    private static Setup91TriggerResearch managed(boolean buy, double risk) {
        return managed(buy, risk, 100);
    }

    private static Setup91TriggerResearch managed(boolean buy, double risk, double atr) {
        return managed(Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R, buy, risk, atr);
    }

    private static Setup91TriggerResearch managed(Mode mode, boolean buy, double risk, double atr) {
        return managedAt(mode, buy, risk, atr, 4_500_000L);
    }

    @Test void rejectedBuyPublishesReasonWithoutInventingPositionOrExit() {
        var events = new java.util.ArrayList<TradeEvent>();
        var tracker = managedAt(Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R, true, 1070, 250, 4_500_000L, events::add);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().stage()).isEqualTo(Stage.REJECTED);
        assertThat(events.getFirst().reason()).isEqualTo("R 1070 fora de 250 a 550 pts");
        assertThat(events.getFirst().points()).isNull();
        assertThat(events.getFirst().exit()).isNull();
        assertThat(tracker.finishDay()).isEmpty();
        assertThat(events).hasSize(1);
    }

    private static Setup91TriggerResearch managedAt(Mode mode, boolean buy, double risk, double atr, long time) {
        return managedAt(mode, buy, risk, atr, time, event -> {});
    }

    private static Setup91TriggerResearch managedAt(Mode mode, boolean buy, double risk, double atr, long time,
                                                                   java.util.function.Consumer<TradeEvent> listener) {
        var tracker = new Setup91TriggerResearch(mode);
        tracker.setTradeListener(listener);
        long bucket = time - time % 300_000L;
        var candles = new java.util.ArrayList<IntrabarCandleSnapshot>();
        for (int i = 0; i < 15; i++) candles.add(new IntrabarCandleSnapshot(
                "WINV26", bucket - (15 - i) * 300_000L, buy ? 1200 : 1800, (buy ? 1200 : 1800) + atr / 2, (buy ? 1200 : 1800) - atr / 2, buy ? 1200 : 1800));
        tracker.warmUp("WINV26", bucket, candles);
        tracker.onLast(new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent("WINV26", time, buy ? 1200 : 1800), 1, 1336, 1));
        var candle = new IntrabarCandleSnapshot("WINV26", bucket - 300_000,
                buy ? 1200 : 1800, buy ? 1200 : 1800 + risk,
                buy ? 1200 - risk : 1800, buy ? 1200 : 1800);
        var signal = new Setup91Breakout.SignalCandle(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL,
                candle, 1200, -2, 2, List.of(), Setup91.Direction.FLAT);
        tracker.onTrigger(new Setup91Breakout.Event(buy ? Setup91Breakout.EventType.SETUP91_BUY_TRIGGERED
                : Setup91Breakout.EventType.SETUP91_SELL_TRIGGERED, signal, time, 1, buy ? 1200 : 1800));
        return tracker;
    }

    private static HistoricalCanonicalPriceEvent managedLast(boolean buy, long seq, double price) {
        return new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent("WINV26", 4_500_000,
                buy ? price : 3000 - price), 1, 1336, seq);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void endOfDayDoesNotFabricateAnExitLevelOrCarryOver(boolean buy) {
        var tracker = start(Mode.FIRST_RETURN, buy);
        tracker.onLast(last(buy, 2, 170)); // FIRST_RETURN has no target cap.
        var r = tracker.finishDay().getFirst();
        assertThat(r.exit()).isEqualTo(Exit.DAY_END);
        assertThat(r.mfe()).isEqualTo(60);
        assertThat(r.points()).isEqualTo(60);
        assertThat(tracker.finishDay()).containsExactly(r);
        assertThatIllegalStateException().isThrownBy(() -> tracker.onLast(last(buy, 3, 180)));
    }

    @Test void openPositionSuppressesOverlappingTriggerAndResumesAfterExit() {
        var tracker = start(Mode.FIRST_RETURN, true);
        tracker.onLast(last(true, 2, 110));
        tracker.onTrigger(trigger(true, 2));
        tracker.onLast(last(true, 3, 115));
        tracker.onLast(last(true, 4, 110));
        assertThat(tracker.hasOpenPosition()).isFalse();
        assertThat(tracker.finishDay()).hasSize(1).allSatisfy(r -> {
            assertThat(r.mfe()).isEqualTo(5);
            assertThat(r.exit()).isEqualTo(Exit.RETURN_TO_ENTRY);
        });
    }

    @Test void validatesOrderingAndStaleTriggersWhenFlat() {
        var tracker = start(Mode.FIRST_RETURN, true);
        assertThatIllegalArgumentException().isThrownBy(() -> tracker.onLast(last(true, 1, 120)));
        assertThatCode(() -> tracker.onTrigger(trigger(true, 1))).doesNotThrowAnyException();
        tracker.onLast(last(true, 2, 90));
        assertThatIllegalArgumentException().isThrownBy(() -> tracker.onTrigger(trigger(true, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> tracker.onTrigger(trigger(true, 2)));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void openManagedPositionDoesNotEvaluateOrLogRejectedTriggers(boolean buy) {
        var tracker = managed(buy, 200);
        var events = new java.util.ArrayList<TradeEvent>();
        tracker.setTradeListener(events::add);
        assertThat(tracker.hasOpenPosition()).isTrue();
        tracker.onLast(managedLast(buy, 2, 1200));
        // An invalid/stale trigger would fail validation when flat; while positioned it is ignored.
        assertThatCode(() -> tracker.onTrigger(trigger(!buy, 2))).doesNotThrowAnyException();
        assertThat(events).isEmpty();
        tracker.onLast(managedLast(buy, 3, 1000));
        assertThat(tracker.hasOpenPosition()).isFalse();
        assertThat(events).extracting(TradeEvent::stage).containsExactly(Stage.EXIT);
        assertThat(tracker.finishDay()).hasSize(1);
    }

    @Test void freshTriggerCanEnterAfterCurrentPositionCloses() {
        var tracker = start(Mode.FIRST_RETURN, true);
        tracker.onLast(last(true, 2, 115));
        tracker.onLast(last(true, 3, 110));
        assertThat(tracker.hasOpenPosition()).isFalse();
        tracker.onTrigger(trigger(true, 3));
        assertThat(tracker.hasOpenPosition()).isTrue();
        tracker.onLast(last(true, 4, 90));
        assertThat(tracker.hasOpenPosition()).isFalse();
        assertThat(tracker.finishDay()).hasSize(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void visualEventsTrackPartialProtectionAndTotalExitWithoutChangingManagement(boolean buy) {
        var tracker = managed(buy, 200);
        var events = new java.util.ArrayList<TradeEvent>();
        tracker.setTradeListener(events::add);
        tracker.onLast(managedLast(buy, 2, 1400));
        assertThat(events).extracting(TradeEvent::stage).containsExactly(Stage.PARTIAL, Stage.BREAKEVEN);
        assertThat(events.getFirst().price()).isEqualTo(buy ? 1400.0 : 1600.0);
        assertThat(events.getFirst().points()).isEqualTo(100.0);
        tracker.onLast(managedLast(buy, 3, 1200));
        assertThat(events.getLast().stage()).isEqualTo(Stage.EXIT);
        assertThat(events.getLast().points()).isEqualTo(tracker.finishDay().getFirst().points());
        assertThat(events.getLast().points()).isEqualTo(100.0);
        assertThat(events.stream().filter(e -> e.stage() == Stage.BREAKEVEN)).hasSize(1);
        int before = events.size();
        tracker.finishDay();
        assertThat(events).hasSize(before);
    }

    @Test void emptyReportHasNoInventedWinnersOrRates() {
        var report = new Setup91TriggerResearchRunner.Report("WINV26", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), Mode.BREAKEVEN_1R_TARGET_2R, List.of());
        assertThat(Setup91TriggerResearchCommand.summary(report)).contains("Total de triggers: 0",
                "MAIOR EXCURSAO FAVORAVEL: N/A", "Parcial de 50% em +1R", "alvo fixo do saldo em +3.5R", "Saldo acumulado de pontos: 0.00");
        assertThat(Setup91TriggerResearchCommand.csv(report).lines().count()).isEqualTo(1);
    }

    private static Setup91TriggerResearch start(Mode mode, boolean buy) {
        var tracker = new Setup91TriggerResearch(mode);
        tracker.onLast(last(buy, 1, 110));
        tracker.onTrigger(trigger(buy, 1));
        return tracker;
    }

    private static HistoricalCanonicalPriceEvent last(boolean buy, long seq, double buyPrice) {
        return new HistoricalCanonicalPriceEvent(new CanonicalPriceEvent("WINV26", 600_000,
                buy ? buyPrice : 200 - buyPrice), 1, 1336, seq);
    }

    private static Setup91Breakout.Event trigger(boolean buy, long sequence) {
        var candle = new IntrabarCandleSnapshot("WINV26", 300_000,
                100, buy ? 100 : 110, buy ? 90 : 100, 100);
        var signal = new Setup91Breakout.SignalCandle(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL,
                candle, 100, buy ? -2 : 2, buy ? 2 : -2, List.of(100., 100., 100., 100., 100.), Setup91.Direction.FLAT);
        return new Setup91Breakout.Event(buy ? Setup91Breakout.EventType.SETUP91_BUY_TRIGGERED
                : Setup91Breakout.EventType.SETUP91_SELL_TRIGGERED, signal, 600_000, sequence, buy ? 110 : 90);
    }
}
