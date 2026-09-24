package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Autonomous finite diagnostic. OLD is a passive tap of the running application, not a replica. */
public final class CanonicalLiveShadowDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    static final int QUEUE_CAPACITY = 20_000;
    record Envelope(Mt5CanonicalPriceStream.Message message, long receivedNanos) {}
    /** Bounded FIFO. Only the isolated source reader waits, never the LIVE callback. */
    static final class CanonicalQueue extends ArrayBlockingQueue<Envelope> {
        private final AtomicLong maximum = new AtomicLong();
        // Full nonblocking offers, recovered by waiting; NOT a count of lost events.
        private final AtomicLong overflows = new AtomicLong();
        private final AtomicLong blockedNanos = new AtomicLong();
        CanonicalQueue() { super(QUEUE_CAPACITY); }
        void enqueue(Mt5CanonicalPriceStream.Message message) {
            var envelope = new Envelope(message, System.nanoTime());
            if (!offer(envelope)) {
                maximum.set(QUEUE_CAPACITY); // producer observes saturation even if consumer is blocked
                overflows.incrementAndGet();
                long began = System.nanoTime();
                try { put(envelope); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Canonical queue producer interrupted during shutdown");
                } finally { blockedNanos.addAndGet(System.nanoTime() - began); }
            }
            maximum.accumulateAndGet(size(), Math::max);
        }
        Map<String, Object> metrics() {
            return Map.of("queueCapacity", QUEUE_CAPACITY, "maxQueueSize", maximum.get(),
                    "overflowCount", overflows.get(), "producerBlockedMillis", blockedNanos.get() / 1e6,
                    "queuePolicy", "BOUNDED_BLOCKING_NO_DROP");
        }
    }

    /** Active loop time includes processing, HTTP and logging, but excludes queue.poll waiting. */
    static final class ConsumerTiming {
        private long activeNanos, maxNanos, iterations;
        void record(long elapsedNanos) {
            activeNanos += elapsedNanos; maxNanos = Math.max(maxNanos, elapsedNanos); iterations++;
        }
        Map<String, Object> metrics(long wallNanos) {
            return Map.of("consumerActiveSeconds", activeNanos / 1e9,
                    "maxConsumerIterationMillis", maxNanos / 1e6, "consumerIterations", iterations,
                    "consumerActivePercentApprox", wallNanos <= 0 ? 0.0 : 100.0 * activeNanos / wallNanos);
        }
    }

    static void stopSource(Mt5CanonicalPriceStream source, Thread worker) throws Exception {
        // A producer in put() must wake before process/reader closure; otherwise close can deadlock.
        worker.interrupt();
        source.close();
        worker.join(6000);
        if (worker.isAlive()) throw new IllegalStateException("Canonical source worker still alive");
    }

    record Options(String symbol, int minutes, int probeSeconds, URI oldUrl, Path report) {
        static Options parse(String[] args) throws Exception {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 == args.length || !Set.of("--symbol", "--duration", "--probe-seconds", "--old-url", "--report").contains(args[i])
                        || values.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Invalid arguments");
            }
            String symbol = values.getOrDefault("--symbol", "WINV26");
            if (!symbol.equals("WINV26")) throw new IllegalArgumentException("The actual OLD pipeline currently uses WINV26 only");
            String duration = values.getOrDefault("--duration", "60m");
            if (!Set.of("30m", "60m").contains(duration)) throw new IllegalArgumentException("duration must be 30m or 60m");
            int probe = Integer.parseInt(values.getOrDefault("--probe-seconds", "30"));
            if (probe < 1 || probe > 60) throw new IllegalArgumentException("probe-seconds must be 1..60");
            URI url = URI.create(values.getOrDefault("--old-url", "http://127.0.0.1:8080"));
            if (!url.getScheme().equals("http") || url.getHost() == null || !InetAddress.getByName(url.getHost()).isLoopbackAddress()
                    || url.getRawQuery() != null || url.getUserInfo() != null || url.getFragment() != null
                    || !(url.getPath().isEmpty() || url.getPath().equals("/"))) throw new IllegalArgumentException("old-url must be a loopback HTTP origin");
            return new Options(symbol, Integer.parseInt(duration.substring(0, 2)), probe,
                    url.resolve("/api/mt5/shadow-capture"), Path.of(values.getOrDefault("--report", "canonical-shadow-report.json")));
        }
    }

    /** Wall time, including waits; never interpreted as CPU time. */
    static final class OperationTiming {
        private final Map<String, long[]> operations = new LinkedHashMap<>();
        <T> T measure(String operation, Callable<T> action) throws Exception {
            long start = System.nanoTime();
            try { return action.call(); }
            finally {
                long elapsed = System.nanoTime() - start;
                long[] values = operations.computeIfAbsent(operation, ignored -> new long[3]);
                values[0] += elapsed; values[1] = Math.max(values[1], elapsed); values[2]++;
            }
        }
        Map<String, Object> metrics() {
            Map<String, Object> result = new LinkedHashMap<>();
            operations.forEach((name, values) -> result.put(name, Map.of(
                    "totalMillis", values[0] / 1e6, "maxMillis", values[1] / 1e6, "calls", values[2])));
            return result;
        }
    }
    private void consumerLog(String text) throws Exception {
        operationTiming.measure("console", () -> { System.out.println(text); return null; });
    }
    private final OperationTiming operationTiming = new OperationTiming();
    private JsonNode oldCapture(HttpClient http, URI uri, String method, String token) throws Exception {
        return operationTiming.measure("oldCapture" + method, () -> call(http, uri, method, token));
    }

    private final IntrabarM5Processor processor = new IntrabarM5Processor();
    private final CanonicalShadowComparator comparator = new CanonicalShadowComparator();
    private final Map<Long, IntrabarMarketState> official = new TreeMap<>();
    private long officialThrough, anchor = Long.MAX_VALUE, sourceWatermark = -1, lastHeaderNanos;
    private long liveEvents, processingNanos, maxProcessingNanos, maxQueueLatencyNanos, maxOldQueue;
    private JsonNode sourceStats = JSON.createObjectNode();
    private boolean header;
    private int printedEvidence;

    private void message(Mt5CanonicalPriceStream.Message message) throws Exception {
        JsonNode data = message.data();
        switch (message.type()) {
            case "header" -> {
                if (header || !data.path("warmup").isArray()) throw new IllegalArgumentException("Invalid/duplicate header");
                String symbol = data.path("symbol").asText();
                long start = requiredLong(data, "startMsc");
                anchor = requiredLong(data, "anchorMsc");
                if (!symbol.equals("WINV26") || M5Bucket.start(anchor) != start) throw new IllegalArgumentException("Invalid bootstrap alignment");
                var bars = JSON.treeToValue(data.get("warmup"), Mt5Candle[].class);
                if (bars.length < 20) throw new IllegalArgumentException("SHADOW_NOT_READY warm-up incomplete");
                processor.warmUp(symbol, start, Arrays.stream(bars).map(c -> Mt5CanonicalPriceMapper.candle(symbol, c)).toList());
                comparator.initialize(anchor + 1);
                header = true; lastHeaderNanos = System.nanoTime();
            }
            case "price" -> {
                if (!header) throw new IllegalArgumentException("Price before bootstrap header");
                var event = message.event();
                long begin = System.nanoTime();
                comparator.canonical(event, processor.onEvent(event));
                long elapsed = System.nanoTime() - begin;
                processingNanos += elapsed; maxProcessingNanos = Math.max(maxProcessingNanos, elapsed);
                if (event.timeMsc() > anchor) liveEvents++;
            }
            case "watermark" -> {
                if (!header) throw new IllegalArgumentException("Watermark before header");
                sourceWatermark = requiredLong(data, "timeMsc");
                comparator.watermark(sourceWatermark);
                sourceStats = data.required("stats");
            }
            case "official" -> {
                if (!header) throw new IllegalArgumentException("Official before header");
                long closedThrough = requiredLong(data, "closedThroughMsc");
                if (closedThrough > sourceWatermark || closedThrough < officialThrough) throw new IllegalArgumentException("Invalid official watermark");
                officialThrough = closedThrough;
                var candles = List.of(JSON.treeToValue(data.required("candles"), Mt5Candle[].class));
                for (var c : candles) if (c.time() * 1000 + M5Bucket.DURATION_MSC > closedThrough)
                    throw new IllegalArgumentException("Official bar is still open");
                // The same TA4J implementation, independent official series, only at bucket close.
                var values = new Ta4jMt5Sma9Adapter().synchronize(candles);
                for (var value : values) {
                    var c = Mt5CanonicalPriceMapper.candle("WINV26", value.candle());
                    if (c.bucketStartTimeMsc() + M5Bucket.DURATION_MSC <= anchor || value.sma21() == null) continue;
                    official.put(c.bucketStartTimeMsc(), new IntrabarMarketState("WINV26",
                            c.bucketStartTimeMsc() + M5Bucket.DURATION_MSC - 1, c.close(), c, value.sma9(), value.sma21(), null));
                }
            }
            case "stopped", "inactive" -> sourceStats = data.required("stats");
            default -> throw new IllegalArgumentException("Unknown message");
        }
    }

    private void compareClosed() {
        official.entrySet().removeIf(entry -> comparator.official(entry.getValue(), officialThrough));
        if (official.size() > 300) comparator.fail(CanonicalShadowComparator.Difference.SOURCE_GAP);
    }

    private static long requiredLong(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.get(field).canConvertToLong()) throw new IllegalArgumentException("Invalid " + field);
        return node.get(field).longValue();
    }

    static boolean inactivityEndsSession(boolean header, long comparisonBegan, long now, long lastProgress) {
        return header && comparisonBegan < 0 && now - lastProgress > 30_000_000_000L;
    }

    static boolean durationEndsSession(long comparisonBegan, long now, int configuredMinutes,
                                       long completeSessionBuckets) {
        long minimumDuration = Duration.ofMinutes(configuredMinutes).toNanos();
        long maximumDuration = minimumDuration + Duration.ofMillis(M5Bucket.DURATION_MSC).toNanos();
        long elapsed = now - comparisonBegan;
        return elapsed >= minimumDuration
                && (completeSessionBuckets >= 6 || elapsed >= maximumDuration);
    }

    public static void main(String[] args) throws Exception { new CanonicalLiveShadowDiagnostics().run(Options.parse(args)); }

    void run(Options options) throws Exception {
        String status = "LIVE_VALIDATION_NOT_RUN", reason = "STARTING", decision = "INSUFFICIENT_EVIDENCE";
        String token = null;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var queue = new CanonicalQueue();
        var consumerTiming = new ConsumerTiming();
        var failure = new AtomicReference<Throwable>();
        long began = System.nanoTime(), comparisonBegan = -1, lastProgress = began, previousLiveEvents = 0;
        long lastPoll = 0, lastSummary = 0;
        long comparisonElapsed = 0;
        try (var source = new Mt5CanonicalPriceStream(options.symbol(), options.probeSeconds())) {
            Thread worker = Thread.ofVirtual().name("canonical-price-source").start(() -> {
                try { source.start(queue::enqueue); }
                catch (Throwable ex) { failure.set(ex); }
            });
            try {
                while (true) {
                    if (failure.get() != null) throw new IllegalStateException("Canonical source failed", failure.get());
                    long now = System.nanoTime();
                    var envelope = operationTiming.measure("queuePoll", () -> queue.poll(20, TimeUnit.MILLISECONDS));
                    long consumerBegan = System.nanoTime();
                    try {
                        if (envelope != null) {
                            maxQueueLatencyNanos = Math.max(maxQueueLatencyNanos, System.nanoTime() - envelope.receivedNanos());
                            operationTiming.measure("message." + envelope.message().type(), () -> { message(envelope.message()); return null; });
                            if (envelope.message().type().equals("inactive")) { reason = "NO_ACTIVE_MARKET"; break; }
                        }
                        if (header && token == null) {
                            token = oldCapture(http, options.oldUrl(), "POST", null).required("token").asText();
                            lastProgress = System.nanoTime();
                        }
                        if (token != null && now - lastPoll >= 100_000_000L) {
                            JsonNode batch = oldCapture(http, options.oldUrl(), "GET", token);
                            if (batch.required("dropped").asLong() != 0) comparator.fail(CanonicalShadowComparator.Difference.SOURCE_GAP);
                            maxOldQueue = Math.max(maxOldQueue, batch.required("maxQueued").asLong());
                            operationTiming.measure("oldObservations", () -> {
                                for (JsonNode value : batch.required("observations")) comparator.old(JSON.treeToValue(value, ShadowObservation.class));
                                return null;
                            });
                            lastPoll = System.nanoTime();
                        }
                        operationTiming.measure("compareClosed", () -> { compareClosed(); return null; });
                        while (printedEvidence < comparator.evidenceCount())
                            consumerLog("[CANONICAL-DIFF] " + JSON.writeValueAsString(comparator.evidence(printedEvidence++)));
                        if (liveEvents > previousLiveEvents) { lastProgress = now; previousLiveEvents = liveEvents; }
                        if (comparisonBegan < 0 && comparator.eventComparisons() > 0) comparisonBegan = now;
                        comparisonElapsed = comparisonBegan < 0 ? 0 : now - comparisonBegan;
                        if (comparisonBegan >= 0 && durationEndsSession(
                                comparisonBegan, now, options.minutes(), comparator.completeSessionBuckets())) {
                            status = "LIVE_VALIDATION_COMPLETED"; reason = "DURATION_REACHED";
                            boolean adequate = comparator.completeSessionBuckets() >= 6 && comparator.fullThreeWayComparisons() >= 6
                                    && official.isEmpty() && comparator.allClosedVerified()
                                    && comparator.lastCompared() - comparator.firstCompared() >= 1_790_000
                                    && now - lastProgress < 30_000_000_000L;
                            decision = adequate ? comparator.officialMatches() ? "SUPPORTS_MIGRATION" : "DOES_NOT_SUPPORT_MIGRATION"
                                    : "INSUFFICIENT_EVIDENCE";
                            break;
                        }
                        if (!header && now - began > Duration.ofSeconds(options.probeSeconds() + 20).toNanos())
                            throw new IllegalStateException("Canonical startup/probe timed out");
                        if (header && comparisonBegan < 0 && now - lastHeaderNanos > 60_000_000_000L) {
                            reason = "SHADOW_NOT_READY_OR_OLD_NOT_RUNNING"; break;
                        }
                        if (inactivityEndsSession(header, comparisonBegan, now, lastProgress)) {
                            status = "LIVE_VALIDATION_INCOMPLETE"; reason = "NO_ACTIVE_MARKET_DURING_SESSION"; break;
                        }
                        if (now - lastSummary > 60_000_000_000L) {
                            consumerLog(String.format("[CANONICAL-SHADOW] durationSeconds=%.1f events=%d eventComparisons=%d bucketsCompared=%d queued=%d readiness=%s%n",
                                    comparisonElapsed / 1e9, liveEvents, comparator.eventComparisons(), comparator.bucketsCompared(), queue.size(), comparator.readiness()));
                            consumerLog("[CANONICAL-SHADOW] differences=" + JSON.writeValueAsString(
                                    Map.of("intrabar", comparator.report().get("oldVsCanonicalIntrabar"),
                                            "canonicalOfficial", comparator.report().get("canonicalVsOfficialClosed"))));
                            lastSummary = now;
                        }
                    } finally { consumerTiming.record(System.nanoTime() - consumerBegan); }
                }
            } catch (Exception ex) {
                status = "LIVE_VALIDATION_FAILED"; reason = ex.toString();
                if (ex.getCause() != null) reason += ": " + ex.getCause();
            } finally {
                try { stopSource(source, worker); }
                catch (Exception ex) { status = "LIVE_VALIDATION_FAILED"; reason = "Shutdown: " + ex; decision = "INSUFFICIENT_EVIDENCE"; }
                if (token != null) {
                    try { oldCapture(http, options.oldUrl(), "DELETE", token); }
                    catch (Exception ex) { System.err.println("Shadow tap will expire automatically: " + ex); }
                }
                for (var remaining : queue) if (remaining.message().type().equals("stopped"))
                    sourceStats = remaining.message().data().required("stats");
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("status", status); report.put("reason", reason); report.put("migrationEvidence", decision);
                report.put("symbol", options.symbol()); report.put("requestedMinutes", options.minutes());
                report.put("comparisonDurationSeconds", comparisonElapsed / 1e9);
                report.put("totalWallSeconds", (System.nanoTime() - began) / 1e9);
                report.put("canonicalLiveEventsAfterBootstrapAnchor", liveEvents);
                report.put("source", sourceStats); report.put("parseErrors", source.parseErrors()); report.put("javaOrderErrors", source.orderErrors());
                report.put("operationTimings", operationTiming.metrics());
                report.putAll(queue.metrics());
                report.putAll(consumerTiming.metrics(System.nanoTime() - began));
                report.put("maxCanonicalQueue", queue.metrics().get("maxQueueSize")); // compatible alias
                report.put("maxOldCaptureQueue", maxOldQueue);
                report.put("maxQueueLatencyMillis", maxQueueLatencyNanos / 1e6);
                report.put("processingSeconds", processingNanos / 1e9); report.put("maxProcessingMicros", maxProcessingNanos / 1e3);
                report.put("liveEventsPerSecond", comparisonElapsed == 0 ? 0 : liveEvents / (comparisonElapsed / 1e9));
                report.put("uncomparedOfficialBuckets", official.size());
                report.put("comparison", comparator.report());
                Path path = options.report().toAbsolutePath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
                System.out.println("[CANONICAL-SHADOW] " + JSON.writeValueAsString(report));
                System.out.println("Report: " + path);
            }
        }
        if (status.equals("LIVE_VALIDATION_FAILED")) throw new IllegalStateException(reason);
    }

    private static JsonNode call(HttpClient http, URI uri, String method, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("X-Shadow-Token", token);
        // The request timeout alone does not bound a stalled body after headers arrive.
        var pending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try { response = pending.get(2, TimeUnit.SECONDS); }
        catch (TimeoutException ex) {
            pending.cancel(true);
            throw new HttpTimeoutException("OLD capture " + method + " response body deadline exceeded (2s)");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw ex;
        }
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("OLD capture HTTP " + response.statusCode()
                + "; enable canonical.shadow.capture-enabled on the running Panic Scanner");
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
}
