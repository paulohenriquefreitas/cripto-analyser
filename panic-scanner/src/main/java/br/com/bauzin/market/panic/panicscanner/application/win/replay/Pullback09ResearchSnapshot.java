package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;

/** Immutable SETUP OUTCOME reference; no simulated entry or trade.
 * SMA values and VWAP include the availability LAST. Slopes and ATR use closed M5 bars only.
 */
public record Pullback09ResearchSnapshot(
        String eventId,
        String symbol,
        long pullb09CandleTimeMsc,
        long setupAvailableTimeMsc,
        double referencePrice,
        long referenceBucket,
        IntrabarCandleSnapshot rj09,
        Double rjMinDistanceToSma9,
        IntrabarCandleSnapshot candle3,
        double confirmationStrength,
        Double sma9,
        Double sma21,
        Double sma9MinusSma21,
        Double sma9Slope,
        Double sma21Slope,
        Double vwap,
        Double distanceToVwap,
        Double atr14,
        Pullback09Setup.EventType setupType,
        IntrabarCandleSnapshot crz09
) {
    public Pullback09ResearchSnapshot(String eventId, String symbol, long pullb09CandleTimeMsc,
            long setupAvailableTimeMsc, double referencePrice, long referenceBucket,
            IntrabarCandleSnapshot rj09, Double rjMinDistanceToSma9, IntrabarCandleSnapshot candle3,
            double confirmationStrength, Double sma9, Double sma21, Double sma9MinusSma21,
            Double sma9Slope, Double sma21Slope, Double vwap, Double distanceToVwap, Double atr14) {
        this(eventId, symbol, pullb09CandleTimeMsc, setupAvailableTimeMsc, referencePrice, referenceBucket,
                rj09, rjMinDistanceToSma9, candle3, confirmationStrength, sma9, sma21, sma9MinusSma21,
                sma9Slope, sma21Slope, vwap, distanceToVwap, atr14, Pullback09Setup.EventType.PULLB09_UP, null);
    }
}
