package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Manual 60-second proof: real Java LIVE pipeline versus a later MT5 range query. */
public final class Mt5TradeIntegrityValidation {
    private static final String SYMBOL = "WINV26";
    private static final long SETTLE_MILLIS = 1_000;
    private static final long HISTORY_SETTLE_MILLIS = 1_000;

    private Mt5TradeIntegrityValidation() {}

    public static void main(String[] args) throws Exception {
        long durationSeconds = duration(args);
        Capture capture = new Capture();
        try (var client = new Mt5TradeStreamClient();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stream = executor.submit(() -> { client.start(capture::accept); return null; });
            capture.awaitAnyTrade();
            Thread.sleep(SETTLE_MILLIS);
            capture.arm();
            capture.awaitStarted();
            Thread.sleep(Duration.ofSeconds(durationSeconds));
            capture.requestEnd();
            capture.awaitEnded();
            client.stop();
            stream.get(10, TimeUnit.SECONDS);
        }

        List<MarketTrade> liveTrades = capture.result();
        long startMsc = liveTrades.getFirst().timeMsc();
        long endMsc = liveTrades.getLast().timeMsc();
        Thread.sleep(HISTORY_SETTLE_MILLIS);
        List<MarketTrade> historicalTrades = historical(startMsc, endMsc);
        List<TradeValidationEvent> live = TradeValidationEvent.fromTrades(liveTrades);
        List<TradeValidationEvent> historical = TradeValidationEvent.fromTrades(historicalTrades);
        TradeSequenceComparison comparison = TradeSequenceComparator.compare(live, historical);
        printReport(durationSeconds, startMsc, endMsc, live, historical, comparison);
        if (!comparison.exact()) System.exit(2);
    }

    private static long duration(String[] args) {
        long value = 60;
        for (int i = 0; i < args.length; i++) {
            if ("--duration-seconds".equals(args[i]) && i + 1 < args.length) value = Long.parseLong(args[++i]);
            else throw new IllegalArgumentException("Uso: --duration-seconds N");
        }
        if (value <= 0) throw new IllegalArgumentException("duration must be positive");
        return value;
    }

    private static List<MarketTrade> historical(long startMsc, long endMsc) throws Exception {
        Path script = locateScript("mt5_trade_history.py");
        Process process = new ProcessBuilder("python", "-u", script.toString(),
                "--symbol", SYMBOL, "--start-msc", Long.toString(startMsc),
                "--end-msc", Long.toString(endMsc)).start();
        List<MarketTrade> result = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.add(Mt5TradeStreamClient.parseLine(line));
        }
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Historical MT5 query did not finish in 30 seconds.");
        }
        if (process.exitValue() != 0) throw new IOException("Historical MT5 query failed: " + error);
        return result;
    }

    private static Path locateScript(String name) throws IOException {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            for (String relative : new String[]{"scripts/" + name, "panic-scanner/scripts/" + name}) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException(name + " not found; run inside the repository.");
    }

    static String report(long durationSeconds, long startMsc, long endMsc,
                         List<TradeValidationEvent> live, List<TradeValidationEvent> historical,
                         TradeSequenceComparison comparison) {
        TradeSequenceStats a = TradeSequenceStats.calculate(live);
        TradeSequenceStats b = TradeSequenceStats.calculate(historical);
        StringBuilder out = new StringBuilder();
        out.append("INTEGRITY VALIDATION\n\nSymbol: ").append(SYMBOL)
                .append("\nDuration: ").append(durationSeconds).append("s")
                .append("\nStart: ").append(formatTime(startMsc)).append(" (").append(startMsc).append(')')
                .append("\nEnd: ").append(formatTime(endMsc)).append(" (").append(endMsc).append(")\n\n");
        appendStats(out, "LIVE", a);
        appendStats(out, "HISTORICAL", b);
        out.append("SEQUENCE\nexactMatches: ").append(comparison.exactMatches())
                .append("\nmissing: ").append(comparison.missing())
                .append("\nextra: ").append(comparison.extra())
                .append("\ndifferent: ").append(comparison.different())
                .append("\norderMismatch: ").append(comparison.orderMismatch())
                .append("\ntimestampMismatch: ").append(comparison.timestampMismatch())
                .append("\npriceMismatch: ").append(comparison.priceMismatch())
                .append("\nvolumeMismatch: ").append(comparison.volumeMismatch())
                .append("\nsideMismatch: ").append(comparison.sideMismatch()).append("\n\n");
        appendMultiplicity(out, "LIVE", a);
        appendMultiplicity(out, "HISTORICAL", b);
        out.append("RESULT\nmatchPercentage: ")
                .append(String.format(Locale.ROOT, "%.3f%%", comparison.matchPercentage()))
                .append("\nexact: ").append(comparison.exact()).append('\n');
        appendFirstDifference(out, comparison);
        return out.toString();
    }

    private static void printReport(long duration, long start, long end,
                                    List<TradeValidationEvent> live,
                                    List<TradeValidationEvent> historical,
                                    TradeSequenceComparison comparison) {
        System.out.print(report(duration, start, end, live, historical, comparison));
    }

    private static void appendStats(StringBuilder out, String title, TradeSequenceStats s) {
        out.append(title).append("\ntrades: ").append(s.trades())
                .append("\nBUY: ").append(s.buyTrades()).append("\nSELL: ").append(s.sellTrades())
                .append("\nAMBIGUOUS: ").append(s.ambiguousTrades()).append("\nvolume: ").append(s.totalVolume())
                .append("\nbuyVolume: ").append(s.buyVolume()).append("\nsellVolume: ").append(s.sellVolume())
                .append("\nambiguousVolume: ").append(s.ambiguousVolume())
                .append("\nknownDelta: ").append(s.knownDelta())
                .append("\nfirstPrice: ").append(s.firstPrice()).append("\nlastPrice: ").append(s.lastPrice())
                .append("\nminPrice: ").append(s.minPrice()).append("\nmaxPrice: ").append(s.maxPrice())
                .append("\npriceChange: ").append(s.priceChange()).append("\n\n");
    }

    private static void appendMultiplicity(StringBuilder out, String title, TradeSequenceStats s) {
        out.append("SAME MILLISECOND (").append(title).append(")")
                .append("\ntimestampsWithMultipleTrades: ").append(s.timestampsWithMultipleTrades())
                .append("\nmaxTradesAtSameTimeMsc: ").append(s.maxTradesAtSameTimeMsc())
                .append("\ntradesSharingTimeMsc: ").append(s.tradesSharingTimeMsc())
                .append("\nidenticalConsecutiveTrades: ").append(s.identicalConsecutiveTrades()).append("\n\n");
    }

    private static void appendFirstDifference(StringBuilder out, TradeSequenceComparison c) {
        if (c.firstDifference() == null) return;
        var first = c.firstDifference();
        out.append("\nFIRST MISMATCH\nkind: ").append(first.kind())
                .append("\nliveIndex: ").append(first.liveIndex())
                .append("\nhistoricalIndex: ").append(first.historicalIndex()).append("\n\nCONTEXT\n");
        int from = Math.max(0, Math.min(first.liveIndex(), first.historicalIndex()) - 3);
        int to = Math.max(first.liveIndex(), first.historicalIndex()) + 3;
        for (int index = from; index <= to; index++) {
            out.append(index).append(" LIVE=").append(at(c.live(), index))
                    .append(" HISTORICAL=").append(at(c.historical(), index)).append('\n');
        }
    }

    private static String at(List<TradeValidationEvent> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index).toString() : "<none>";
    }

    private static String formatTime(long timeMsc) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(timeMsc).atZone(ZoneId.systemDefault()));
    }

    private static final class Capture {
        private enum State { WARMUP, ARMED, CAPTURING, END_REQUESTED, ENDED }
        private final List<MarketTrade> captured = new ArrayList<>();
        private final CountDownLatch anyTrade = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch ended = new CountDownLatch(1);
        private final AtomicLong latest = new AtomicLong(-1);
        private State state = State.WARMUP;
        private long startAfter;
        private long endAt;

        synchronized void accept(MarketTrade trade) {
            latest.set(trade.timeMsc());
            anyTrade.countDown();
            if (state == State.ARMED && trade.timeMsc() > startAfter) {
                state = State.CAPTURING;
                started.countDown();
            }
            if (state == State.END_REQUESTED && trade.timeMsc() > endAt) {
                state = State.ENDED;
                ended.countDown();
                return;
            }
            if (state == State.CAPTURING || state == State.END_REQUESTED) captured.add(trade);
        }

        void awaitAnyTrade() throws InterruptedException {
            if (!anyTrade.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("No MT5 trade received in 30s.");
        }

        synchronized void arm() {
            startAfter = latest.get();
            state = State.ARMED;
        }

        void awaitStarted() throws InterruptedException {
            if (!started.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("No new timestamp started validation in 30s.");
        }

        synchronized void requestEnd() {
            endAt = latest.get();
            state = State.END_REQUESTED;
        }

        void awaitEnded() throws InterruptedException {
            if (!ended.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Could not close the final timestamp group in 30s.");
        }

        synchronized List<MarketTrade> result() { return List.copyOf(captured); }
    }
}
