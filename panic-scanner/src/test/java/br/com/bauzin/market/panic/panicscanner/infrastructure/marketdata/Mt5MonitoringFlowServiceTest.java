package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class Mt5MonitoringFlowServiceTest {
    private static final long SHIFT = 180 * 60_000L;
    private static final long RAW = 1_792_587_042_098L;
    private static class Clock {
        long utc = RAW + SHIFT;
        long nanos;
        void advance(long millis) { utc += millis; nanos += millis * 1_000_000; }
        Mt5MonitoringFlowService service() {
            return new Mt5MonitoringFlowService(new Mt5Symbol("WINZ26"), 180, true, () -> utc, () -> nanos);
        }
    }
    private static MarketTrade trade(long timestamp, double price) {
        return new MarketTrade("WINZ26", timestamp, price, 50, AggressorSide.BUY);
    }
    @Test void recentRawTradeUsesConfiguredClockWithoutChangingTimestamp() {
        var clock = new Clock();
        var service = clock.service();
        var trade = trade(RAW, 100);
        service.onTrade(trade);
        var value = service.snapshot();
        assertThat(value.timeMsc()).isEqualTo(RAW);
        assertThat(value.lastTimeMsc()).isEqualTo(trade.timeMsc());
        assertThat(value.windows()).allSatisfy(w -> {
            assertThat(w.events()).isEqualTo(1);
            assertThat(w.buyVolume()).isEqualTo(50);
            assertThat(w.quality()).isEqualTo("WARMING_UP");
        });
        assertThat(value.timeBasis().feedTimeShiftMinutes()).isEqualTo(180);
    }
    @Test void exactWindowBoundariesExpireAtFiveTenAndThirtySeconds() {
        var clock = new Clock();
        var service = clock.service();
        service.onTrade(trade(RAW, 100));
        for (int age : new int[]{4999, 5000, 9999, 10000, 29999, 30000}) {
            clock.advance(RAW + SHIFT + age - clock.utc);
            for (var w : service.snapshot().windows()) {
                assertThat(w.events()).as("age %s window %s", age, w.seconds())
                        .isEqualTo(age < w.seconds() * 1000 ? 1 : 0);
                if (age >= w.seconds() * 1000) assertThat(w.quality()).isEqualTo("NO_TRADES");
            }
        }
    }
    @Test void historyBacklogAndFutureCannotRefreshLiveClock() {
        var clock = new Clock();
        var service = clock.service();
        service.connectionStatus(true, RAW - 86_400_000);
        service.onTrade(trade(RAW - 86_400_000, 100));
        service.onTrade(trade(RAW - 30_000, 100));
        service.onTrade(trade(RAW + 2001, 100));
        var value = service.snapshot();
        assertThat(value.timeMsc()).isEqualTo(RAW);
        assertThat(value.windows()).allMatch(w -> w.events() == 0 && w.alert() == null);
        assertThat(value.timeBasis().rejectedOld()).isEqualTo(2);
        assertThat(value.timeBasis().rejectedFuture()).isEqualTo(1);
        // A delayed trade within retention keeps its actual age, never receipt time.
        service.onTrade(trade(RAW - 10_000, 100));
        assertThat(service.snapshot().windows().get(0).events()).isZero();
        assertThat(service.snapshot().windows().get(2).quality()).isEqualTo("STALE");
    }
    @Test void freshTradesResumeAfterIdleWithoutReplayingOldPrices() {
        var clock = new Clock();
        var service = clock.service();
        service.onTrade(trade(RAW, 100));
        clock.advance(60_000);
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 0);
        service.onTrade(trade(RAW + 60_000, 105));
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 1
                && !w.quality().equals("STALE") && w.lastDisplacement() == null);
    }
    @Test void freshnessAndDisplacementShareTheRawFeedReference() {
        var clock = new Clock();
        var service = clock.service();
        service.onTrade(trade(RAW, 100));
        clock.advance(30_000);
        service.onTrade(trade(RAW + 30_000, 105));
        assertThat(service.snapshot().windows().get(2).lastDisplacement()).isEqualTo(5);
        assertThat(service.snapshot().windows().get(2).quality()).isEqualTo("GOOD");
        clock.advance(2001);
        assertThat(service.snapshot().windows().get(2).quality()).isEqualTo("STALE");
        assertThat(service.snapshot().windows().get(2).lastDisplacement()).isNull();
    }
    @Test void clockAndTradeRegressionAreContainedAndRestartRecovers() {
        var clock = new Clock();
        var service = clock.service();
        service.onTrade(trade(RAW, 100));
        clock.advance(1000);
        service.onTrade(trade(RAW + 1000, 101));
        service.onTrade(trade(RAW + 500, 99));
        assertThat(service.snapshot().timeBasis().rejectedRegression()).isEqualTo(1);
        clock.utc -= 60_000;
        clock.nanos += 5_000_000_000L;
        assertThat(service.snapshot().timeMsc()).isEqualTo(RAW + 6000);
        assertThat(service.snapshot().windows().get(0).events()).isZero();
        var restarted = clock.service();
        restarted.onTrade(trade(clock.utc - SHIFT, 102));
        assertThat(restarted.snapshot().windows()).allMatch(w -> w.events() == 1);
        assertThat(restarted.snapshot().timeBasis().rejectedRegression()).isZero();
    }
    @Test void temporalConfigurationChangeResetsAllFlowBuffersAndValidation() {
        var clock = new Clock();
        var service = clock.service();
        service.onTrade(trade(RAW, 100));
        service.invalidVolume(RAW);
        service.configureTimeBasis(0, false);
        var value = service.snapshot();
        assertThat(value.timeMsc()).isEqualTo(clock.utc);
        assertThat(value.lastTimeMsc()).isNull();
        assertThat(value.timeBasis().confirmed()).isFalse();
        assertThat(value.windows()).allMatch(w -> w.events() == 0 && w.quality().equals("NO_TRADES"));
        service.onTrade(trade(clock.utc, 105));
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 1);
        service.configureTimeBasis(0, false); // Idempotent reload retains current data.
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 1);
        service.configureTimeBasis(0, true);
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 0);
    }
    @Test void integrityAndDisconnectedStatusRemainVisible() {
        var clock = new Clock();
        var service = clock.service();
        service.invalidVolume(RAW);
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("INVALID_VOLUME"));
        service.disconnected();
        assertThat(service.snapshot().windows()).allMatch(w -> w.quality().equals("DISCONNECTED"));
        assertThatThrownBy(() -> service.onTrade(new MarketTrade("WINV26", RAW, 100, 1,
                AggressorSide.BUY))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void subMillisecondPollingDoesNotStopMonotonicAdvanceAfterUtcRollback() {
        var clock = new Clock();
        var service = clock.service();
        service.snapshot();
        clock.utc -= 60_000;
        for (int i = 0; i < 10_000; i++) {
            clock.nanos += 100_000;
            service.snapshot();
        }
        assertThat(service.snapshot().timeMsc()).isEqualTo(RAW + 1000);
    }
    @Test void shortSimulationOfSixtySecondsNeedsNoWallClockWaitOrMarketConnection() {
        var clock = new Clock();
        var service = clock.service();
        service.connectionStatus(true, RAW);
        for (int step = 0; step < 240; step++) {
            int eventsSoFar = step + 1;
            long raw = clock.utc - SHIFT;
            service.onTrade(trade(raw, 100 + step % 2));
            var value = service.snapshot();
            assertThat(value.timeMsc()).isEqualTo(raw);
            assertThat(value.windows()).allSatisfy(w -> {
                assertThat(w.events()).isEqualTo(Math.min(eventsSoFar, w.seconds() * 4));
                assertThat(w.quality()).isNotIn("NO_TRADES", "STALE");
            });
            clock.advance(250);
        }
        clock.advance(30_000);
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 0);
        service.onTrade(trade(clock.utc - SHIFT, 105));
        assertThat(service.snapshot().windows()).allMatch(w -> w.events() == 1);
    }
    @Test void unvalidatedBasisIsExplicitInEveryServiceSnapshot() {
        var clock = new Clock();
        var service = new Mt5MonitoringFlowService(new Mt5Symbol("WINZ26"), 180, false,
                () -> clock.utc, () -> clock.nanos);
        service.onTrade(trade(RAW, 100));
        assertThat(service.snapshot().timeBasis().confirmed()).isFalse();
    }
}
