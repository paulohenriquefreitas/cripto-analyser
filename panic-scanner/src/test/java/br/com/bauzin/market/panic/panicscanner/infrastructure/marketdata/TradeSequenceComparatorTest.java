package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeSequenceComparatorTest {
    @Test
    void equalSequencesMatchExactly() {
        var values = events(trade(1, 100, 1, AggressorSide.BUY),
                trade(2, 101, 2, AggressorSide.SELL));
        var result = TradeSequenceComparator.compare(values, values);
        assertTrue(result.exact());
        assertEquals(100.0, result.matchPercentage());
        assertEquals(2, result.exactMatches());
    }

    @Test
    void detectsTradeMissingFromLiveWithoutCascadingMismatches() {
        var a = trade(1, 100, 1, AggressorSide.BUY);
        var b = trade(2, 101, 1, AggressorSide.BUY);
        var c = trade(3, 102, 1, AggressorSide.SELL);
        var d = trade(4, 103, 1, AggressorSide.SELL);
        var result = TradeSequenceComparator.compare(events(a, b, d), events(a, b, c, d));
        assertEquals(1, result.missing());
        assertEquals(0, result.extra());
        assertEquals(3, result.exactMatches());
    }

    @Test
    void detectsExtraTradeInLiveWithoutCascadingMismatches() {
        var a = trade(1, 100, 1, AggressorSide.BUY);
        var x = trade(2, 999, 1, AggressorSide.BUY);
        var b = trade(3, 101, 1, AggressorSide.SELL);
        var result = TradeSequenceComparator.compare(events(a, x, b), events(a, b));
        assertEquals(1, result.extra());
        assertEquals(0, result.missing());
        assertEquals(2, result.exactMatches());
    }

    @Test
    void detectsIndividualFieldDifferences() {
        var live = events(trade(1, 100, 1, AggressorSide.BUY));
        assertEquals(1, TradeSequenceComparator.compare(live,
                events(trade(2, 100, 1, AggressorSide.BUY))).timestampMismatch());
        assertEquals(1, TradeSequenceComparator.compare(live,
                events(trade(1, 101, 1, AggressorSide.BUY))).priceMismatch());
        assertEquals(1, TradeSequenceComparator.compare(live,
                events(trade(1, 100, 2, AggressorSide.BUY))).volumeMismatch());
        assertEquals(1, TradeSequenceComparator.compare(live,
                events(trade(1, 100, 1, AggressorSide.SELL))).sideMismatch());
    }

    @Test
    void preservesLegitimateIdenticalTradesAndSameTimestampOrdinals() {
        MarketTrade a = trade(1, 100, 1, AggressorSide.BUY);
        var values = events(a, a, a, trade(1, 100, 2, AggressorSide.SELL));
        var result = TradeSequenceComparator.compare(values, values);
        var stats = TradeSequenceStats.calculate(values);
        assertTrue(result.exact());
        assertEquals(List.of(0, 1, 2, 3), values.stream().map(
                TradeValidationEvent::ordinalWithinTimestamp).toList());
        assertEquals(1, stats.timestampsWithMultipleTrades());
        assertEquals(4, stats.maxTradesAtSameTimeMsc());
        assertEquals(4, stats.tradesSharingTimeMsc());
        assertEquals(2, stats.identicalConsecutiveTrades());
    }

    @Test
    void detectsReorderedAdjacentEventsSeparatelyFromMissingAndExtra() {
        var a = trade(1, 100, 1, AggressorSide.BUY);
        var b = trade(2, 101, 1, AggressorSide.SELL);
        var result = TradeSequenceComparator.compare(events(b, a), events(a, b));
        assertEquals(2, result.orderMismatch());
        assertEquals(0, result.missing());
        assertEquals(0, result.extra());
        assertEquals(0, result.different());
    }

    @Test
    void calculatesSideVolumeDeltaAndPricesExplicitly() {
        var stats = TradeSequenceStats.calculate(events(
                trade(1, 100, 10, AggressorSide.BUY),
                trade(2, 98, 5, AggressorSide.SELL),
                trade(3, 103, 20, AggressorSide.AMBIGUOUS)));
        assertEquals(35, stats.totalVolume());
        assertEquals(10, stats.buyVolume());
        assertEquals(5, stats.sellVolume());
        assertEquals(20, stats.ambiguousVolume());
        assertEquals(5, stats.knownDelta());
        assertEquals(100, stats.firstPrice());
        assertEquals(103, stats.lastPrice());
        assertEquals(98, stats.minPrice());
        assertEquals(103, stats.maxPrice());
        assertEquals(3, stats.priceChange());
    }

    private static List<TradeValidationEvent> events(MarketTrade... trades) {
        return TradeValidationEvent.fromTrades(List.of(trades));
    }

    private static MarketTrade trade(long time, double price, double volume, AggressorSide side) {
        return new MarketTrade("WINV26", time, price, volume, side);
    }
}
