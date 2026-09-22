package br.com.bauzin.market.panic.panicscanner.application.win.flow;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine.FIVE_SECONDS;
import static br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine.ONE_SECOND;
import static br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine.TEN_SECONDS;
import static br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine.THREE_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class TradeFlowEngineTest {
    private static final double TOLERANCE = 1e-9;

    @Test
    void aggregatesBuyVolumeAndPositiveDelta() {
        TradeFlowEngine engine = engineWith(
                trade(1_000, 100, 10, AggressorSide.BUY),
                trade(1_001, 100, 20, AggressorSide.BUY));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.buyVolume()).isEqualTo(30);
        assertThat(snapshot.sellVolume()).isZero();
        assertThat(snapshot.knownDelta()).isEqualTo(30);
    }

    @Test
    void aggregatesSellVolumeAndNegativeDelta() {
        TradeFlowEngine engine = engineWith(
                trade(1_000, 100, 10, AggressorSide.SELL),
                trade(1_001, 100, 20, AggressorSide.SELL));

        assertThat(engine.snapshot(FIVE_SECONDS).knownDelta()).isEqualTo(-30);
    }

    @Test
    void calculatesMixedKnownFlow() {
        TradeFlowEngine engine = engineWith(
                trade(1_000, 100, 10, AggressorSide.BUY),
                trade(1_001, 100, 4, AggressorSide.SELL));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.knownVolume()).isEqualTo(14);
        assertThat(snapshot.knownDelta()).isEqualTo(6);
        assertThat(snapshot.buyShare()).isCloseTo(10.0 / 14, offset(TOLERANCE));
        assertThat(snapshot.sellShare()).isCloseTo(4.0 / 14, offset(TOLERANCE));
    }

    @Test
    void keepsAmbiguousVolumeOutOfKnownVolumeAndDelta() {
        TradeFlowEngine engine = engineWith(
                trade(1_000, 100, 10, AggressorSide.BUY),
                trade(1_001, 100, 5, AggressorSide.SELL),
                trade(1_002, 100, 20, AggressorSide.AMBIGUOUS));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.totalVolume()).isEqualTo(35);
        assertThat(snapshot.knownVolume()).isEqualTo(15);
        assertThat(snapshot.knownDelta()).isEqualTo(5);
        assertThat(snapshot.ambiguousVolume()).isEqualTo(20);
    }

    @Test
    void preservesMultiplicityAndOrderAtSameMillisecond() {
        TradeFlowEngine engine = new TradeFlowEngine();
        MarketTrade repeated = trade(1_000, 100, 1, AggressorSide.BUY);
        engine.onTrade(repeated);
        engine.onTrade(repeated);
        engine.onTrade(trade(1_000, 99, 2, AggressorSide.SELL));
        engine.onTrade(trade(1_000, 101, 5, AggressorSide.BUY));

        FlowSnapshot snapshot = engine.snapshot(ONE_SECOND);
        assertThat(snapshot.tradeCount()).isEqualTo(4);
        assertThat(snapshot.buyVolume()).isEqualTo(7);
        assertThat(snapshot.sellVolume()).isEqualTo(2);
        assertThat(snapshot.knownDelta()).isEqualTo(5);
        assertThat(snapshot.firstPrice()).hasValue(100);
        assertThat(snapshot.lastPrice()).hasValue(101);
    }

    @Test
    void expiresEachWindowIndependently() {
        TradeFlowEngine engine = engineWith(
                trade(0, 100, 1, AggressorSide.BUY),
                trade(2_000, 101, 2, AggressorSide.BUY),
                trade(4_000, 102, 4, AggressorSide.BUY),
                trade(6_000, 103, 8, AggressorSide.BUY),
                trade(10_000, 104, 16, AggressorSide.BUY));

        assertWindow(engine.snapshot(ONE_SECOND), 1, 16);
        assertWindow(engine.snapshot(THREE_SECONDS), 1, 16);
        assertWindow(engine.snapshot(FIVE_SECONDS), 2, 24);
        assertWindow(engine.snapshot(TEN_SECONDS), 4, 30);
    }

    @Test
    void excludesTradeExactlyAtOpenBoundary() {
        TradeFlowEngine engine = engineWith(
                trade(5_000, 100, 1, AggressorSide.BUY),
                trade(5_001, 101, 2, AggressorSide.BUY),
                trade(10_000, 102, 4, AggressorSide.BUY));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.fromTimeMsc()).isEqualTo(5_000);
        assertThat(snapshot.toTimeMsc()).isEqualTo(10_000);
        assertThat(snapshot.tradeCount()).isEqualTo(2);
        assertThat(snapshot.totalVolume()).isEqualTo(6);
        assertThat(snapshot.firstPrice()).hasValue(101);
    }

    @Test
    void rejectsOutOfOrderTradeWithoutChangingState() {
        TradeFlowEngine engine = engineWith(trade(2_000, 100, 1, AggressorSide.BUY));

        assertThatThrownBy(() -> engine.onTrade(trade(1_999, 99, 9, AggressorSide.SELL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-decreasing");
        assertThat(engine.snapshot(TEN_SECONDS).tradeCount()).isEqualTo(1);
        assertThat(engine.snapshot(TEN_SECONDS).sellVolume()).isZero();
    }

    @Test
    void returnsFiniteZeroSharesWhenKnownVolumeIsZero() {
        TradeFlowEngine engine = engineWith(trade(1_000, 100, 20, AggressorSide.AMBIGUOUS));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.knownVolume()).isZero();
        assertThat(snapshot.buyShare()).isZero().isFinite();
        assertThat(snapshot.sellShare()).isZero().isFinite();
        assertThat(snapshot.knownDelta()).isZero();
    }

    @Test
    void calculatesPriceAndNominalWindowActivityMetrics() {
        TradeFlowEngine engine = engineWith(
                trade(1_000, 100, 2, AggressorSide.BUY),
                trade(1_100, 105, 3, AggressorSide.SELL),
                trade(1_200, 98, 4, AggressorSide.BUY),
                trade(1_300, 103, 1, AggressorSide.BUY));

        FlowSnapshot snapshot = engine.snapshot(FIVE_SECONDS);
        assertThat(snapshot.firstPrice()).hasValue(100);
        assertThat(snapshot.lastPrice()).hasValue(103);
        assertThat(snapshot.minPrice()).hasValue(98);
        assertThat(snapshot.maxPrice()).hasValue(105);
        assertThat(snapshot.priceChange()).hasValue(3);
        assertThat(snapshot.priceRange()).hasValue(7);
        assertThat(snapshot.priceVelocity()).hasValue(0.6);
        assertThat(snapshot.tradesPerSecond()).isEqualTo(0.8);
        assertThat(snapshot.contractsPerSecond()).isEqualTo(2);
    }

    @Test
    void producesSameSnapshotsRegardlessOfDeliverySpeed() throws Exception {
        List<MarketTrade> dataset = List.of(
                trade(1_000, 100, 3, AggressorSide.BUY),
                trade(1_000, 101, 2, AggressorSide.AMBIGUOUS),
                trade(2_500, 99, 4, AggressorSide.SELL),
                trade(7_000, 102, 5, AggressorSide.BUY));
        TradeFlowEngine immediate = new TradeFlowEngine();
        TradeFlowEngine delayed = new TradeFlowEngine();
        for (MarketTrade trade : dataset) {
            immediate.onTrade(trade);
        }
        for (MarketTrade trade : dataset) {
            Thread.sleep(1);
            delayed.onTrade(trade);
        }

        for (Duration window : List.of(ONE_SECOND, THREE_SECONDS, FIVE_SECONDS, TEN_SECONDS)) {
            assertThat(delayed.snapshot(window)).isEqualTo(immediate.snapshot(window));
        }
    }

    @Test
    void emptyAndResetSnapshotsHaveNoInventedPrices() {
        TradeFlowEngine engine = new TradeFlowEngine();
        FlowSnapshot empty = engine.snapshot(FIVE_SECONDS);
        assertThat(empty.tradeCount()).isZero();
        assertThat(empty.totalVolume()).isZero();
        assertThat(empty.firstPrice()).isEmpty();
        assertThat(empty.priceVelocity()).isEmpty();

        engine.onTrade(trade(1_000, 100, 1, AggressorSide.BUY));
        engine.reset();
        assertThat(engine.snapshot(FIVE_SECONDS)).isEqualTo(empty);
        engine.onTrade(new MarketTrade("WINZ26", 500, 110, 2, AggressorSide.SELL));
        assertThat(engine.snapshot(FIVE_SECONDS).tradeCount()).isEqualTo(1);
    }

    @Test
    void rejectsSymbolMixingUntilReset() {
        TradeFlowEngine engine = engineWith(trade(1_000, 100, 1, AggressorSide.BUY));

        assertThatThrownBy(() -> engine.onTrade(new MarketTrade(
                "WINZ26", 1_001, 101, 2, AggressorSide.SELL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset first");
        assertThat(engine.snapshot(FIVE_SECONDS).tradeCount()).isEqualTo(1);
    }

    @Test
    void processesOneHundredThousandTradesWithBoundedFinalWindows() {
        TradeFlowEngine engine = new TradeFlowEngine();
        for (int i = 0; i < 100_000; i++) {
            AggressorSide side = AggressorSide.values()[i % AggressorSide.values().length];
            engine.onTrade(trade(i, 100 + i % 11, 1, side));
        }

        assertThat(engine.snapshot(ONE_SECOND).tradeCount()).isEqualTo(1_000);
        assertThat(engine.snapshot(THREE_SECONDS).tradeCount()).isEqualTo(3_000);
        assertThat(engine.snapshot(FIVE_SECONDS).tradeCount()).isEqualTo(5_000);
        FlowSnapshot tenSeconds = engine.snapshot(TEN_SECONDS);
        assertThat(tenSeconds.tradeCount()).isEqualTo(10_000);
        assertThat(tenSeconds.totalVolume()).isEqualTo(10_000);
        assertThat(tenSeconds.minPrice()).hasValue(100);
        assertThat(tenSeconds.maxPrice()).hasValue(110);
    }

    @Test
    void validatesTradeAndSupportedWindow() {
        assertThatThrownBy(() -> new MarketTrade(" ", 0, 100, 1, AggressorSide.BUY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarketTrade("WINV26", 0, Double.NaN, 1, AggressorSide.BUY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MarketTrade("WINV26", 0, 100, 0, AggressorSide.BUY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeFlowEngine().snapshot(Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }

    private static TradeFlowEngine engineWith(MarketTrade... trades) {
        TradeFlowEngine engine = new TradeFlowEngine();
        for (MarketTrade trade : trades) {
            engine.onTrade(trade);
        }
        return engine;
    }

    private static MarketTrade trade(long timeMsc, double price, double volume, AggressorSide side) {
        return new MarketTrade("WINV26", timeMsc, price, volume, side);
    }

    private static void assertWindow(FlowSnapshot snapshot, long trades, double volume) {
        assertThat(snapshot.tradeCount()).isEqualTo(trades);
        assertThat(snapshot.totalVolume()).isEqualTo(volume);
    }
}
