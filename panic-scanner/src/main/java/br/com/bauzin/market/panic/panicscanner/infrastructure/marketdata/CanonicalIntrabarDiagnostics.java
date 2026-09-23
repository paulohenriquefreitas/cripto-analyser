package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only historical diagnostic, not a backtest or a visual replay controller. */
public final class CanonicalIntrabarDiagnostics {
    private final IntrabarM5Processor processor = new IntrabarM5Processor();
    private final MarketStructureEngine engine = new MarketStructureEngine();
    private final Map<Long, IntrabarCandleSnapshot> official = new LinkedHashMap<>();
    private long sma9Samples, sma21Samples, interactions, structureEvents, candles, compared, missing;
    private long openDifferences, highDifferences, lowDifferences, closeDifferences;

    private void initialize(Mt5HistoricalPriceSource.Header header) {
        processor.warmUp(header.symbol(), header.startMsc(), header.warmup().stream()
                .map(c -> Mt5CanonicalPriceMapper.candle(header.symbol(), c)).toList());
        for (var candle : header.official()) {
            var snapshot = Mt5CanonicalPriceMapper.candle(header.symbol(), candle);
            if (snapshot.bucketStartTimeMsc() < header.startMsc() || snapshot.bucketStartTimeMsc() >= header.endMsc()
                    || official.putIfAbsent(snapshot.bucketStartTimeMsc(), snapshot) != null)
                throw new IllegalArgumentException("Invalid official candle interval or duplicate");
        }
        System.out.printf("symbol=%s startMsc=%d endMscExclusive=%d warmupBars=%d%n",
                header.symbol(), header.startMsc(), header.endMsc(), processor.retainedBarCount());
    }

    private void accept(CanonicalPriceEvent event) {
        var state = processor.onEvent(event);
        if (state.completedCandle() != null) compare(state.completedCandle());
        if (state.sma9() != null) sma9Samples++;
        if (state.sma21() != null) sma21Samples++;
        state.structureSample().ifPresent(sample -> {
            var update = engine.onSample(sample);
            interactions += update.completedInteractions().size();
            structureEvents += update.events().size();
        });
    }

    private void compare(IntrabarCandleSnapshot candle) {
        candles++;
        var expected = official.remove(candle.bucketStartTimeMsc());
        if (expected == null) { missing++; return; }
        compared++;
        double dOpen = candle.open() - expected.open(), dHigh = candle.high() - expected.high();
        double dLow = candle.low() - expected.low(), dClose = candle.close() - expected.close();
        if (dOpen != 0) openDifferences++;
        if (dHigh != 0) highDifferences++;
        if (dLow != 0) lowDifferences++;
        if (dClose != 0) closeDifferences++;
        System.out.printf("bucket=%d deltaOpen=%s deltaHigh=%s deltaLow=%s deltaClose=%s%n",
                candle.bucketStartTimeMsc(), dOpen, dHigh, dLow, dClose);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: CanonicalIntrabarDiagnostics history.ndjson");
        long started = System.nanoTime();
        var diagnostic = new CanonicalIntrabarDiagnostics();
        Mt5HistoricalPriceSource.Statistics stats;
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) {
            stats = new Mt5HistoricalPriceSource().stream(reader, diagnostic::initialize, diagnostic::accept);
        }
        // Header end is an exclusive M5 boundary: the final observed bar is closed as of that boundary.
        if (diagnostic.processor.currentCandle() != null) diagnostic.compare(diagnostic.processor.currentCandle());
        System.out.printf("eventsRead=%d LASTEvents=%d candles=%d sma9Samples=%d sma21Samples=%d "
                        + "completedInteractions=%d structureEvents=%d compared=%d missingOfficial=%d "
                        + "officialWithoutReconstruction=%d diffOpen=%d diffHigh=%d diffLow=%d diffClose=%d wallClockSeconds=%.6f%n",
                stats.eventsRead(), stats.lastEvents(), diagnostic.candles, diagnostic.sma9Samples,
                diagnostic.sma21Samples, diagnostic.interactions, diagnostic.structureEvents, diagnostic.compared,
                diagnostic.missing, diagnostic.official.size(), diagnostic.openDifferences, diagnostic.highDifferences,
                diagnostic.lowDifferences, diagnostic.closeDifferences, (System.nanoTime() - started) / 1e9);
    }
}
