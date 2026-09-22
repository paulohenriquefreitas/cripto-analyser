package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

public record MovingAverageSnapshot(
        PriceReference reference,
        MovingAverageState state,
        long timeMsc,
        double price,
        double movingAverage,
        double distance,
        double absoluteDistance,
        boolean interactionActive) {
}
