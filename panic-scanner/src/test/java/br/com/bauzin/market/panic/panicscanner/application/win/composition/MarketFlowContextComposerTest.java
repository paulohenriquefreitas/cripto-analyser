package br.com.bauzin.market.panic.panicscanner.application.win.composition;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.FlowAtTimeProvider;
import br.com.bauzin.market.panic.panicscanner.application.win.flow.RecentTradeBuffer;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshotSet;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.StructuralContext;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureEventType;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageState;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketFlowContextComposerTest {
    private static final String SYMBOL = "WINV26";
    private static final long T = 10_000;

    @Test
    void neverIncludesTradeAfterStructuralEvent() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T - 1, 100));
        buffer.add(trade(T + 1, 101));

        FlowSnapshotSet snapshots = new FlowAtTimeProvider(buffer).snapshotAt(SYMBOL, T);

        assertThat(snapshots.oneSecond().tradeCount()).isEqualTo(1);
        assertThat(snapshots.tenSeconds().tradeCount()).isEqualTo(1);
        assertThat(snapshots.tenSeconds().toTimeMsc()).isEqualTo(T);
        assertThat(snapshots.maxTradeTimeMscUsed()).isEqualTo(T - 1);
    }

    @Test
    void includesTradeExactlyAtEventTime() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T, 100));

        FlowSnapshotSet snapshots = new FlowAtTimeProvider(buffer).snapshotAt(SYMBOL, T);

        assertThat(snapshots.oneSecond().tradeCount()).isEqualTo(1);
        assertThat(snapshots.maxTradeTimeMscUsed()).isEqualTo(T);
    }

    @Test
    void preservesMultipleTradesAtSameMillisecond() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T, 100));
        buffer.add(new MarketTrade(SYMBOL, T, 101, 2, AggressorSide.SELL));
        buffer.add(trade(T, 102));

        FlowSnapshotSet snapshots = new FlowAtTimeProvider(buffer).snapshotAt(SYMBOL, T);

        assertThat(snapshots.oneSecond().tradeCount()).isEqualTo(3);
        assertThat(snapshots.oneSecond().totalVolume()).isEqualTo(4);
    }

    @Test
    void windowsUseExactEventTimeBounds() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T - 10_000, 90));
        buffer.add(trade(T - 5_000, 95));
        buffer.add(trade(T - 1_000, 99));
        buffer.add(trade(T, 100));

        FlowSnapshotSet snapshots = new FlowAtTimeProvider(buffer).snapshotAt(SYMBOL, T);

        assertThat(snapshots.oneSecond().tradeCount()).isEqualTo(1);
        assertThat(snapshots.threeSeconds().tradeCount()).isEqualTo(2);
        assertThat(snapshots.fiveSeconds().tradeCount()).isEqualTo(2);
        assertThat(snapshots.tenSeconds().tradeCount()).isEqualTo(3);
        assertThat(snapshots.oneSecond().fromTimeMsc()).isEqualTo(T - 1_000);
        assertThat(snapshots.oneSecond().toTimeMsc()).isEqualTo(T);
    }

    @Test
    void eventLookupRemainsCausalAfterBufferReceivesFutureTrades() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T - 500, 100));
        buffer.add(trade(T + 100, 200));

        StructuralContext context = compose(buffer, T);

        assertThat(context.flow().tenSeconds().tradeCount()).isEqualTo(1);
        assertThat(context.maxFlowTradeTimeMscUsed()).isEqualTo(T - 500);
    }

    @Test
    void rejectsOutOfOrderWithoutCorruptingState() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T, 100));

        assertThatThrownBy(() -> buffer.add(trade(T - 1, 99)))
                .isInstanceOf(IllegalArgumentException.class);
        buffer.add(trade(T, 101));

        assertThat(buffer.size()).isEqualTo(2);
    }

    @Test
    void rejectsDifferentSymbolsWithoutMixing() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T, 100));

        assertThatThrownBy(() -> buffer.add(new MarketTrade("WINZ26", T, 100, 1, AggressorSide.BUY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowAtTimeProvider(buffer).snapshotAt("WINZ26", T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    void sameSequenceProducesDeterministicSnapshots() {
        List<MarketTrade> sequence = List.of(
                trade(T - 4_000, 100),
                trade(T - 1_000, 101),
                trade(T, 102));
        RecentTradeBuffer first = new RecentTradeBuffer();
        RecentTradeBuffer second = new RecentTradeBuffer();
        sequence.forEach(first::add);
        sequence.forEach(second::add);

        assertThat(new FlowAtTimeProvider(first).snapshotAt(SYMBOL, T))
                .isEqualTo(new FlowAtTimeProvider(second).snapshotAt(SYMBOL, T));
    }

    @Test
    void composerCreatesOneContextPerStructuralEvent() {
        RecentTradeBuffer buffer = new RecentTradeBuffer();
        buffer.add(trade(T, 100));
        var event = new MarketStructureEvent(
                MarketStructureEventType.TOUCH_DETECTED, PriceReference.SMA21,
                MovingAverageState.TOUCHING, T, 100, 100, 0, PriceSide.AT);

        List<StructuralContext> contexts = new MarketFlowContextComposer(
                SYMBOL, new FlowAtTimeProvider(buffer))
                .compose(new MarketStructureUpdate(null, null, List.of(event), List.of()));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.getFirst().structuralEvent()).isEqualTo(event);
        assertThat(contexts.getFirst().flow().oneSecond().tradeCount()).isEqualTo(1);
    }

    private static StructuralContext compose(RecentTradeBuffer buffer, long timeMsc) {
        var event = new MarketStructureEvent(
                MarketStructureEventType.NEAR_ENTERED, PriceReference.SMA21,
                MovingAverageState.NEAR, timeMsc, 100, 100, 0, PriceSide.AT);
        return new MarketFlowContextComposer(SYMBOL, new FlowAtTimeProvider(buffer))
                .compose(new MarketStructureUpdate(null, null, List.of(event), List.of()))
                .getFirst();
    }

    private static MarketTrade trade(long timeMsc, double price) {
        return new MarketTrade(SYMBOL, timeMsc, price, 1, AggressorSide.BUY);
    }
}
