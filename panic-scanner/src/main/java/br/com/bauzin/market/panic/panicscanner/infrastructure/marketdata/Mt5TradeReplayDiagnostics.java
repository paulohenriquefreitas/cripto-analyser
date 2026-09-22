package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.TradeReplayObserver;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.TradeReplayRunner;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplayRunResult;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public final class Mt5TradeReplayDiagnostics {
    private Mt5TradeReplayDiagnostics() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        ReplaySession session = new ReplaySession(
                options.getOrDefault("symbol", "WINV26"),
                requiredLong(options, "load-start-msc"),
                requiredLong(options, "analysis-start-msc"),
                requiredLong(options, "analysis-end-msc"));
        ReplayRunResult result = new TradeReplayRunner(new TradeFlowEngine()).run(
                session, new Mt5HistoricalTradeSource(session), TradeReplayObserver.NONE);
        print(result);
    }

    static void print(ReplayRunResult result) {
        var session = result.session();
        var stats = result.tradeStats();
        System.out.println("PANIC REPLAY\n");
        System.out.println("Symbol: " + session.symbol());
        System.out.println("Load start: " + session.loadStartTimeMsc());
        System.out.println("Analysis start: " + session.analysisStartTimeMsc());
        System.out.println("Analysis end: " + session.analysisEndTimeMsc());
        System.out.println("\nTRADE REPLAY");
        System.out.println("eventsRead: " + result.eventsRead());
        System.out.println("warmupEvents: " + result.warmupEvents());
        System.out.println("trades: " + stats.trades());
        System.out.println("BUY: " + stats.buyTrades());
        System.out.println("SELL: " + stats.sellTrades());
        System.out.println("AMBIGUOUS: " + stats.ambiguousTrades());
        System.out.println("volume: " + stats.totalVolume());
        System.out.println("knownDelta: " + stats.knownDelta());
        System.out.println("firstPrice: " + optional(stats.firstPrice()));
        System.out.println("lastPrice: " + optional(stats.lastPrice()));
        System.out.println("minPrice: " + optional(stats.minPrice()));
        System.out.println("maxPrice: " + optional(stats.maxPrice()));
        System.out.println("\nPERFORMANCE");
        System.out.println("marketDuration: " + result.marketDuration());
        System.out.println("wallClock: " + result.wallClockDuration());
        System.out.printf(Locale.ROOT, "eventsPerSecond: %.2f%n", result.eventsPerSecond());
        System.out.println("\nMARKET STRUCTURE");
        System.out.println("historical source: NOT YET VALIDATED");
    }

    private static String optional(java.util.OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "N/A";
    }

    private static long requiredLong(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Missing --" + key);
        return Long.parseLong(value);
    }

    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("Options require values.");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Invalid option: " + args[i]);
            result.put(args[i].substring(2), args[i + 1]);
        }
        return result;
    }
}
