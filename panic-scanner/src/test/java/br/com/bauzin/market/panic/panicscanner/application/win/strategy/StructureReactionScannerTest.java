package br.com.bauzin.market.panic.panicscanner.application.win.strategy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;

class StructureReactionScannerTest {
    @Test void reversalNeedsLaterTimestampAndOpposingFlowAndIsDeduplicated() {
        var s = new StructureReactionScanner(); var levels = List.of(1000d);
        assertThat(s.checkReaction(1, 990, levels, 400)).isEmpty();
        assertThat(s.checkReaction(1, 990, levels, -400)).isEmpty();
        assertThat(s.checkReaction(2, 989, levels, -400)).get()
                .extracting(StructureReactionEvent::reactionType).isEqualTo("TOUCH_REVERSAL");
        assertThat(s.checkReaction(3, 990, levels, -400)).isEmpty();
    }
    @Test void breakoutRequiresCrossingStrictDistanceAndDirectionalThreshold() {
        var s = new StructureReactionScanner(); var levels = List.of(1000d);
        s.checkReaction(1, 980, levels, 0);
        assertThat(s.checkReaction(2, 1020, levels, 400)).isEmpty();
        assertThat(s.checkReaction(3, 1021, levels, 400)).get()
                .extracting(StructureReactionEvent::reactionType).isEqualTo("BREAKOUT");
    }
    @Test void supportReversalUsesPositiveDelta() {
        var s = new StructureReactionScanner(); var levels = List.of(1000d);
        s.checkReaction(1, 1010, levels, -400);
        assertThat(s.checkReaction(2, 1011, levels, 400)).get()
                .extracting(StructureReactionEvent::levelType).isEqualTo("PIVOT_BOTTOM");
    }
    @Test void supportBreakoutNeedsNegativeFlowAndNoSignalFromWeakDelta() {
        var s = new StructureReactionScanner(); var levels = List.of(1000d);
        s.checkReaction(1, 1020, levels, 0);
        assertThat(s.checkReaction(2, 979, levels, -299)).isEmpty();
        s.checkReaction(3, 981, levels, 0);
        assertThat(s.checkReaction(4, 979, levels, -300)).get()
                .extracting(StructureReactionEvent::reactionType).isEqualTo("BREAKOUT");
    }
    @Test void removedLevelCannotReact() {
        var s = new StructureReactionScanner();
        s.checkReaction(1, 990, List.of(1000d), 0);
        assertThat(s.checkReaction(2, 989, List.of(), -400)).isEmpty();
    }
    private IntrabarCandleSnapshot candle(int index, double high, double close) {
        return new IntrabarCandleSnapshot("WINZ26", index * 300000L, 990, high, 980, close);
    }
    @Test void pivotRequiresFiveClosedCandlesAndRetiresOnlyOnCloseBeyond() {
        var s = new StructureReactionScanner();
        s.onCandleClosed(candle(0, 995, 990)); s.onCandleClosed(candle(1, 996, 990));
        s.onCandleClosed(candle(2, 1000, 990)); s.onCandleClosed(candle(3, 996, 990));
        assertThat(s.checkReaction(1200000, 990, 0)).isEmpty();
        assertThat(s.checkReaction(1200001, 989, -400)).isEmpty();
        s.onCandleClosed(candle(4, 995, 990));
        s.checkReaction(1500000, 990, 0);
        assertThat(s.checkReaction(1500001, 989, -400)).isPresent();
        var retired = new StructureReactionScanner();
        retired.onCandleClosed(candle(0,995,990)); retired.onCandleClosed(candle(1,996,990));
        retired.onCandleClosed(candle(2,1000,990)); retired.onCandleClosed(candle(3,996,990));
        retired.onCandleClosed(candle(4,995,990)); retired.onCandleClosed(candle(5,1030,1025));
        retired.checkReaction(1800000, 990, 0);
        assertThat(retired.checkReaction(1800001, 989, -400)).isEmpty();
    }
    @Test void rejectsRegressingClockAndInvalidConfiguration() {
        var s = new StructureReactionScanner(); s.checkReaction(2, 1000, 0);
        assertThatThrownBy(() -> s.checkReaction(1, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructureReactionScanner(15,20,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
