package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.time.Duration;

public record ReplayRunResult(
        ReplaySession session,
        long eventsRead,
        long eventsProcessed,
        long warmupEvents,
        long analysisEvents,
        Duration marketDuration,
        Duration wallClockDuration,
        double eventsPerSecond,
        TradeReplayStats tradeStats) {
}
