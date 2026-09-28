package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** One CSV row per confirmed PULLB09. All excursions are SETUP OUTCOME, never trade P&L. */
public record Pullback09ResearchRecord(Pullback09ResearchSnapshot snapshot, Pullback09SetupOutcome outcome) {
    public static String csvHeader() {
        return "eventId,symbol,date,pullb09CandleTimeMsc,setupAvailableTimeMsc,referencePrice,referenceBucket,"
                + "rjOpen,rjHigh,rjLow,rjClose,rjMinDistanceToSma9,candle3Open,candle3High,candle3Low,candle3Close,"
                + "confirmationStrength,sma9,sma21,sma9MinusSma21,sma9Slope,sma21Slope,vwap,distanceToVwap,atr14,"
                + "setupMfe5,setupMae5,setupMfe15,setupMae15,setupMfe30,setupMae30,"
                + "outcome5Status,outcome15Status,outcome30Status";
    }

    public String toCsvRow() {
        var s = snapshot;
        var r = s.rj09();
        var c = s.candle3();
        return Arrays.stream(new Object[]{
                s.eventId(), s.symbol(), Instant.ofEpochMilli(s.setupAvailableTimeMsc()).atZone(ZoneOffset.UTC).toLocalDate(),
                s.pullb09CandleTimeMsc(), s.setupAvailableTimeMsc(), s.referencePrice(), s.referenceBucket(),
                r.open(), r.high(), r.low(), r.close(), s.rjMinDistanceToSma9(),
                c.open(), c.high(), c.low(), c.close(), s.confirmationStrength(),
                s.sma9(), s.sma21(), s.sma9MinusSma21(), s.sma9Slope(), s.sma21Slope(),
                s.vwap(), s.distanceToVwap(), s.atr14(),
                outcome.mfe5m(), outcome.mae5m(), outcome.mfe15m(), outcome.mae15m(), outcome.mfe30m(), outcome.mae30m(),
                status(outcome.complete5m()), status(outcome.complete15m()), status(outcome.complete30m())
        }).map(Pullback09ResearchRecord::cell).collect(Collectors.joining(","));
    }

    private static String status(boolean complete) { return complete ? "COMPLETE" : "INCOMPLETE"; }

    private static String cell(Object value) {
        if (value == null || value instanceof Double d && !Double.isFinite(d)) return "";
        String text = value.toString();
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public static String toCsv(List<Pullback09ResearchRecord> records) {
        return csvHeader() + "\n" + records.stream().map(Pullback09ResearchRecord::toCsvRow)
                .collect(Collectors.joining("\n", "", records.isEmpty() ? "" : "\n"));
    }
}
