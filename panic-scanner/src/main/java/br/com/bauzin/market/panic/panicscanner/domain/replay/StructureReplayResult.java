package br.com.bauzin.market.panic.panicscanner.domain.replay;

import java.time.Duration;

public record StructureReplayResult(
        ReplaySession session,
        long samplesRead,
        long warmupSamples,
        long analysisSamples,
        long sma9Interactions,
        long sma21Interactions,
        long sma9Touched,
        long sma21Touched,
        long sma9Crossed,
        long sma21Crossed,
        long excludedWarmupInteractions,
        Duration wallClockDuration) {
}
