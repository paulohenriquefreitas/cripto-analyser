package br.com.bauzin.market.panic.panicscanner.application.experimental;

import br.com.bauzin.market.panic.panicscanner.application.engine.TechnicalAnalysisEngine;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.ExperimentalEntryAnalysis;
import java.math.BigDecimal;
import java.util.*;

/** Frozen v1 generic hypotheses. All confirmation bars must already be closed. */
public final class ExperimentalEntryAnalyzer {
    public static final String VERSION = "early-entry-v1";

    public ExperimentalEntryAnalysis analyze(TechnicalAnalysis a, List<Candle> source,
                                             TechnicalAnalysisEngine engine, BigDecimal minimumLiquidity) {
        List<Candle> c = source.stream().filter(b -> b.status() == CandleStatus.CLOSED
                && !b.date().isAfter(a.analysisDate())).sorted(Comparator.comparing(Candle::date)).toList();
        Map<String, Boolean> checks = new LinkedHashMap<>();
        // Extra warm-up is for this hypothesis only; the current scanner still requires 21 bars.
        if (c.size() < 35 || a.adxSma5() == null || !c.getLast().date().equals(a.analysisDate()))
            return new ExperimentalEntryAnalysis("INCONCLUSIVE", List.of(), Map.of("history", false),
                    List.of("At least 35 closed candles and experimental indicators are required"));
        int n = c.size();
        TechnicalAnalysis p = engine.analyze(a.ticker(), a.timeframe(), c.subList(0, n - 1));
        TechnicalAnalysis pp = engine.analyze(a.ticker(), a.timeframe(), c.subList(0, n - 2));
        Candle last = c.getLast(), previous = c.get(n - 2), before = c.get(n - 3);
        checks.put("liquidity", a.averageFinancialVolume20().compareTo(minimumLiquidity) >= 0);
        checks.put("rsi45to72", between(a.rsi9(), 45, 72));
        checks.put("extension", a.distanceFromEma21Percent().compareTo(bd(6)) <= 0
                && a.atr14().signum() > 0 && a.lastClose().subtract(a.ema21()).compareTo(a.atr14().multiply(bd(3))) <= 0);
        checks.put("EARLY_REVERSAL.priceRecovery", gt(a.lastClose(), a.ema9()) && gt(last.close(), previous.high()));
        checks.put("EARLY_REVERSAL.momentumStrengthening", gt(a.rsi9(), p.rsi9()) && gt(p.rsi9(), pp.rsi9()));
        checks.put("EARLY_REVERSAL.averagesReacting", gt(a.ema9(), p.ema9()) && gt(a.sma9(), p.sma9())
                && (gt(a.sma21(), p.sma21()) || gt(a.movingAverageSlope21(), p.movingAverageSlope21())));
        checks.put("EARLY_REVERSAL.recentReclaim", p.lastClose().compareTo(p.ema9()) <= 0
                || pp.lastClose().compareTo(pp.ema9()) <= 0);
        checks.put("ADX_RECOVERY.below20AboveSma5", a.adx14().compareTo(bd(20)) < 0 && gt(a.adx14(), a.adxSma5()));
        checks.put("ADX_RECOVERY.consistentStrengthening", Boolean.TRUE.equals(a.adxRising())
                && Boolean.TRUE.equals(p.adxRising()) && Boolean.TRUE.equals(a.adxSma5Rising()));
        checks.put("ADX_RECOVERY.bullishDirection", Boolean.TRUE.equals(a.plusDiAboveMinusDi())
                && gt(a.ema9(), a.ema21()) && gt(a.lastClose(), a.ema21()));
        // Rejection at D-1, confirmation at D: no same-bar or future pivot confirmation.
        checks.put("PULLBACK_REENTRY.uptrend", gt(p.ema9(), p.ema21()) && gt(p.sma9(), p.sma21())
                && p.movingAverageSlope21().signum() > 0);
        boolean rejection = false;
        for (BigDecimal support : List.of(p.ema9(), p.ema21(), p.sma21())) {
            boolean correction = previous.low().compareTo(before.low()) < 0
                    && previous.low().compareTo(before.close()) < 0;
            boolean touch = previous.low().compareTo(support.multiply(bd(1.01))) <= 0
                    && previous.low().compareTo(support.multiply(bd(.99))) >= 0;
            boolean held = previous.close().compareTo(support) >= 0 && gt(previous.close(), previous.open());
            rejection |= correction && touch && held;
        }
        checks.put("PULLBACK_REENTRY.supportRejection", rejection);
        checks.put("PULLBACK_REENTRY.laterConfirmation", gt(last.close(), previous.high())
                && last.low().compareTo(previous.low()) >= 0 && gt(last.close(), a.ema9()));
        List<String> patterns = new ArrayList<>();
        boolean gates = checks.get("liquidity") && checks.get("rsi45to72") && checks.get("extension");
        if (gates) for (String pattern : List.of("EARLY_REVERSAL", "ADX_RECOVERY", "PULLBACK_REENTRY")) {
            if (checks.entrySet().stream().filter(e -> e.getKey().startsWith(pattern + ".")).allMatch(Map.Entry::getValue))
                patterns.add(pattern);
        }
        List<String> reasons = patterns.isEmpty() ? checks.entrySet().stream().filter(e -> !e.getValue())
                .map(e -> "Failed: " + e.getKey()).toList() : patterns.stream().map(s -> "Confirmed closed-bar hypothesis: " + s).toList();
        return new ExperimentalEntryAnalysis(patterns.isEmpty() ? "NONE" : "ANALYZE_ENTRY", patterns, checks, reasons);
    }

    private static boolean gt(BigDecimal a, BigDecimal b) { return a.compareTo(b) > 0; }
    private static BigDecimal bd(double d) { return BigDecimal.valueOf(d); }
    private static boolean between(BigDecimal v, int low, int high) { return v.compareTo(bd(low)) >= 0 && v.compareTo(bd(high)) <= 0; }
}
