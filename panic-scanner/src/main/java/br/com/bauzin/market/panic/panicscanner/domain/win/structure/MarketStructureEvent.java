package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

public record MarketStructureEvent(
        MarketStructureEventType type,
        PriceReference reference,
        MovingAverageState state,
        long timeMsc,
        double price,
        double movingAverage,
        double distance,
        PriceSide side) {
}
