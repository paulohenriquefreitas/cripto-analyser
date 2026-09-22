package br.com.bauzin.market.panic.panicscanner.domain.win.structure;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Completed objective measurement. It is not a trading signal or rejection classification. */
public record MovingAverageInteraction(
        String symbol,
        PriceReference reference,
        PriceSide approachSide,
        long startTimeMsc,
        long nearTimeMsc,
        OptionalLong touchTimeMsc,
        OptionalLong crossTimeMsc,
        long movingAwayTimeMsc,
        long endTimeMsc,
        double approachStartPrice,
        double approachStartDistance,
        double movingAverageAtNear,
        OptionalDouble movingAverageAtTouch,
        double movingAverageAtExit,
        double minimumAbsoluteDistance,
        double priceAtClosestApproach,
        long timeAtClosestApproachMsc,
        boolean touched,
        boolean crossed,
        double maxPenetrationPoints,
        PriceSide exitSide,
        double exitPrice,
        double exitDistance,
        long durationMsc,
        long timeNearAverageMsc) {
}
