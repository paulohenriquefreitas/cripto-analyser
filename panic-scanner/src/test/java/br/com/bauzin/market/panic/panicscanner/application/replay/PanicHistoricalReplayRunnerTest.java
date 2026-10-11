package br.com.bauzin.market.panic.panicscanner.application.replay;

import br.com.bauzin.market.panic.panicscanner.application.*;
import br.com.bauzin.market.panic.panicscanner.application.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.*;
import br.com.bauzin.market.panic.panicscanner.domain.entry.*;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.*;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PanicHistoricalReplayRunnerTest {
    private final PanicScannerProperties props = new PanicScannerProperties(null, 0, 0, null, null, null, 0, null, null, 0);
    private final PanicScannerEntryProperties entryProps = new PanicScannerEntryProperties(
            0, 0, 0, null, null, null, 0, null, null, null, null, 0, null, null, null, null, null, null, 0, null, null, null, null, 0, null);
    private final LocalDate from = LocalDate.of(2026, 8, 3);
    private final LocalDate to = LocalDate.of(2026, 9, 30);
    private final List<Candle> history = history();
    private int discoveryCalls;

    private List<Candle> history() {
        List<Candle> candles = new ArrayList<>();
        int index = 0;
        for (var day = LocalDate.of(2026, 5, 1); !day.isAfter(LocalDate.of(2026, 10, 2)); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            var close = BigDecimal.valueOf(10 + (++index) * .03 + Math.sin(index) * .2);
            candles.add(new Candle(day, close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                    BigDecimal.valueOf(5_000_000)));
        }
        return candles;
    }

    private PanicHistoricalReplayRunner runner(List<Candle> data) {
        // Deliberately malicious source: ignores the requested date range.
        MarketDataClient source = new MarketDataClient() {
            public List<StockSummary> listStocks() {
                discoveryCalls++;
                return List.of(new StockSummary("MGLU3", "Magazine Luiza", "STOCK", true));
            }
            public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) { return data; }
        };
        var config = new MomentumScoringConfig(props.minAverageFinancialVolume20(), props.minimumRsi(), props.maximumRsi(),
                props.minimumAdx(), props.minimumQualifiedScore(), props.maximumHealthyDistanceFromSma21Percent(), props.preferredRelativeVolume());
        return new PanicHistoricalReplayRunner(source, new Ta4jTechnicalAnalysisAdapter(),
                new MomentumScannerStrategy(config, new ScoreCalculator()),
                new DefaultEntryAnalyzer(entryProps.toConfig(), new EntryScoreCalculator()), props);
    }

    private PanicHistoricalReplayRunner.Request request() {
        return new PanicHistoricalReplayRunner.Request(from, to, List.of("mglu3"), RankingMode.MOMENTUM, "mt5");
    }

    @Test
    void changingAndAddingFutureCandlesCannotChangePastDayIncludingEntriesAndRanking() {
        var day = LocalDate.of(2026, 8, 31);
        var baseline = runner(history).run(request()).stream().filter(r -> !r.simulatedDate().isAfter(day)).toList();
        List<Candle> mutated = new ArrayList<>();
        for (var c : history) {
            mutated.add(c.date().isAfter(day) ? new Candle(c.date(), BigDecimal.valueOf(1000),
                    BigDecimal.valueOf(2000), BigDecimal.ONE, BigDecimal.valueOf(1500), BigDecimal.valueOf(999_999_999)) : c);
        }
        mutated.add(new Candle(LocalDate.of(2027, 1, 4), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        var replay = runner(mutated).run(request()).stream().filter(r -> !r.simulatedDate().isAfter(day)).toList();
        assertFalse(baseline.isEmpty());
        assertEquals(baseline, replay); // complete ScannerResult, entry checks, scores, reasons, rank and changes
        assertEquals(0, discoveryCalls);
    }

    @Test
    void extendingReplayUpperBoundDoesNotChangeEarlierResults() {
        var shortRun = runner(history).run(new PanicHistoricalReplayRunner.Request(from,
                LocalDate.of(2026, 8, 31), List.of("MGLU3"), RankingMode.ENTRY, "mt5"));
        var longRun = runner(history).run(new PanicHistoricalReplayRunner.Request(from, to,
                List.of("MGLU3"), RankingMode.ENTRY, "mt5"));
        assertEquals(shortRun, longRun.subList(0, shortRun.size()));
    }

    @Test
    void onlyClosedSessionsAreSimulatedAndClockIsEndOfSession() {
        var partialDate = LocalDate.of(2026, 8, 4);
        var withPartial = history.stream().map(c -> c.date().equals(partialDate)
                ? new Candle(c.date(), c.open(), c.high(), c.low(), c.close(), c.volume(), CandleStatus.INTRADAY_PARTIAL, null) : c).toList();
        var rows = runner(withPartial).run(request());
        assertTrue(rows.stream().noneMatch(r -> r.simulatedDate().equals(partialDate)));
        for (var r : rows) {
            assertEquals(r.simulatedDate(), r.result().analysisDate());
            assertEquals(LocalTime.MAX, r.simulatedAt().toLocalTime());
            assertEquals(ZoneOffset.ofHours(-3), r.simulatedAt().getOffset());
            assertEquals(r.simulatedAt(), r.result().marketDataUpdatedAt());
            assertNull(r.result().currentPartialCandle());
        }
    }

    @Test
    void partialAndFutureDataNeverReachBoundedPortEvenWhenAskedForFuture() {
        var day = LocalDate.of(2026, 8, 3);
        var clock = Clock.fixed(day.atTime(LocalTime.MAX).atZone(PanicHistoricalReplayRunner.MARKET_ZONE).toInstant(),
                PanicHistoricalReplayRunner.MARKET_ZONE);
        var client = PanicHistoricalReplayRunner.bounded(Map.of("MGLU3", history), day, clock);
        assertTrue(client.getDailyCandles("MGLU3", day.minusDays(90), to).stream().allMatch(c -> !c.date().isAfter(day)));
    }

    @Test
    void optionalUniverseIsDiscoveredAndReportsPreserveAllChecks(@TempDir Path output) throws Exception {
        var req = new PanicHistoricalReplayRunner.Request(from, to, List.of(), RankingMode.BREAKOUT, "mt5");
        var rows = runner(history).run(req);
        assertEquals(1, discoveryCalls);
        PanicReplayReport.write(output, req, rows);
        String csv = Files.readString(output.resolve("radar.csv"));
        assertTrue(csv.contains("result.technicalAnalysis.sma9"));
        assertTrue(csv.contains("result.checks.rsiInsideRange"));
        assertTrue(csv.contains("result.entryAnalysis.checks.breakoutVolumeAccepted"));
        assertTrue(csv.contains("2026-08-03"));
        assertEquals(rows.size() + 1, csv.lines().count());
        assertEquals(rows.size(), Files.readAllLines(output.resolve("radar.ndjson")).size());
    }

    @Test
    void invalidCliOptionsAndIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PanicHistoricalReplayRunner.Request(to, from, List.of(), null, "mt5"));
        assertThrows(IllegalArgumentException.class, () -> PanicReplayCommand.options(new String[]{"--from"}));
        assertThrows(IllegalArgumentException.class, () -> PanicReplayCommand.options(new String[]{"--unknown", "value"}));
    }
}
