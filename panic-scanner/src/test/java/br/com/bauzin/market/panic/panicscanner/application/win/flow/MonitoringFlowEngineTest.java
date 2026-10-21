package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MonitoringFlowEngineTest {
    private MonitoringFlowEngine engine() {
        var engine = new MonitoringFlowEngine("WINV26");
        engine.connected(true, 0);
        engine.onLast(100, 0);
        return engine;
    }
    private MarketTrade trade(long time, double price, double volume, AggressorSide side) {
        return new MarketTrade("WINV26", time, price, volume, side);
    }
    @Test void windowsDeltaUnknownAndDisplacementHaveTheSameEventClock() {
        var engine = engine();
        engine.onTrade(trade(1_000, 100, 10, AggressorSide.BUY), 1);
        engine.onTrade(trade(4_000, 102, 4, AggressorSide.SELL), 2);
        engine.onTrade(trade(8_000, 102, 6, AggressorSide.AMBIGUOUS), 3);
        engine.onTrade(trade(10_000, 103, 20, AggressorSide.BUY), 4);
        var snapshot = engine.snapshot(10_000);
        var five = snapshot.windows().get(0);
        assertThat(five.buyVolume()).isEqualTo(20);
        assertThat(five.sellVolume()).isZero();
        assertThat(five.unknownVolume()).isEqualTo(6);
        assertThat(five.delta()).isEqualTo(20);
        assertThat(five.unknownPercent()).isCloseTo(600.0 / 26, within(1e-9));
        assertThat(five.lastDisplacement()).isEqualTo(1);
        var ten = snapshot.windows().get(1);
        assertThat(ten.buyVolume()).isEqualTo(30);
        assertThat(ten.sellVolume()).isEqualTo(4);
        assertThat(ten.delta()).isEqualTo(26);
        assertThat(ten.lastDisplacement()).isEqualTo(3);
        assertThat(snapshot.windows().get(2).quality()).isEqualTo("WARMING_UP");
    }
    @Test void idleWindowsExpireWithoutFakeTradesOrZeroMovement() {
        var engine = engine();
        engine.onTrade(trade(1000, 100, 10, AggressorSide.BUY), 1);
        var snapshot = engine.snapshot(31_001);
        for (var window : snapshot.windows()) {
            assertThat(window.events()).isZero();
            assertThat(window.delta()).isZero();
            assertThat(window.unknownPercent()).isNull();
            assertThat(window.lastDisplacement()).isNull();
            assertThat(window.quality()).isEqualTo("NO_TRADES");
            assertThat(window.alert()).isNull();
            assertThat(window.toTimeMsc()).isEqualTo(31_001);
        }
    }
    @Test void cursorPreservesRealIdenticalTradesAndRejectsReprocessing() {
        var engine = engine();
        var trade = trade(1000, 100, 2, AggressorSide.BUY);
        assertThat(engine.onTrade(trade, 1)).isTrue();
        assertThat(engine.onTrade(trade, 1)).isFalse();
        assertThat(engine.onTrade(trade, 2)).isTrue();
        engine.connected(false, 1001);
        assertThat(engine.snapshot(1001).windows().get(0).quality()).isEqualTo("DISCONNECTED");
        engine.connected(true, 1002);
        assertThat(engine.onTrade(trade, 2)).isFalse();
        assertThat(engine.onTrade(trade, 3)).isTrue();
        assertThat(engine.snapshot(1002).windows().get(0).buyVolume()).isEqualTo(6);
    }
    @Test void fullPrebufferAllowsDescriptiveAlertButInsufficientQualitySuppressesIt() {
        var engine = engine();
        for (int i = 0; i <= 30; i++) engine.onTrade(trade(i * 1000L, 100, 10, AggressorSide.BUY), i);
        var good = engine.snapshot(30_000);
        for (var window : good.windows()) {
            assertThat(window.quality()).isEqualTo("GOOD");
            assertThat(window.alert()).isEqualTo("POSSIBLE_ABSORPTION");
            assertThat(window.lastDisplacement()).isZero();
        }
        engine.onTrade(trade(30_001, 100, 500, AggressorSide.AMBIGUOUS), 31);
        for (var window : engine.snapshot(30_001).windows()) {
            assertThat(window.quality()).isEqualTo("HIGH_UNKNOWN");
            assertThat(window.alert()).isNull();
        }
        engine.invalidVolume(30_002);
        assertThat(engine.snapshot(30_002).windows()).allMatch(w -> w.quality().equals("INVALID_VOLUME") && w.alert() == null);
    }
    @Test void priceChangeNeverDeterminesAggressorAndRealLastCanSuppressAbsorption() {
        var engine = engine();
        for (int i = 0; i <= 30; i++) engine.onTrade(trade(i * 1000L, 100, 10, AggressorSide.SELL), i);
        engine.onLast(110, 30_001);
        var snapshot = engine.snapshot(30_001);
        assertThat(snapshot.windows()).allMatch(w -> w.delta() < 0 && w.lastDisplacement() == 10 && w.alert() == null);
        assertThat(snapshot.last()).isEqualTo(110);
        assertThat(snapshot.lastTimeMsc()).isEqualTo(30_001);
        engine.connected(false, 30_001);
        assertThat(engine.snapshot(30_001).windows()).allMatch(w -> w.quality().equals("DISCONNECTED"));
    }
    @Test void unvalidatedContinuousSymbolNeverProducesInterpretation() {
        var engine = new MonitoringFlowEngine("WIN$N");
        engine.connected(true, 0);
        engine.onTrade(new MarketTrade("WIN$N", 1000, 100, 10, AggressorSide.BUY), 1);
        assertThat(engine.snapshot(1000).windows()).allMatch(w -> w.quality().equals("UNVALIDATED_SYMBOL") && w.alert() == null);
    }
}
