package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Single-use blocking client for the independent copy_ticks_* trade stream. */
public final class Mt5TradeStreamClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Mt5TradeStreamClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private final ProcessStarter starter;
    private final String expectedSymbol;
    public record Cursor(long timeMsc, long ordinal) {}
    public record Checkpoint(String symbol, Cursor cursor) {}
    private volatile Cursor cursor;
    private boolean cursorRequired;
    private Process process;
    private boolean started;
    private volatile boolean stopping;
    private final AtomicLong tradesReceived = new AtomicLong();
    private final AtomicLong buyTrades = new AtomicLong();
    private final AtomicLong sellTrades = new AtomicLong();
    private final AtomicLong ambiguousTrades = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong outOfOrderErrors = new AtomicLong();
    private final AtomicLong lastTradeTimeMsc = new AtomicLong(-1);

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    public Mt5TradeStreamClient() {
        this(Mt5Symbol.defaults(), null);
    }

    public Mt5TradeStreamClient(Mt5Symbol symbol, Checkpoint previous) {
        this(symbol, previous, cursor -> startPython(symbol,cursor));
    }

    @FunctionalInterface interface CursorStarter { Process start(Cursor cursor) throws IOException; }
    Mt5TradeStreamClient(Mt5Symbol symbol, Checkpoint previous, CursorStarter factory) {
        this.cursor = previous != null && symbol.value().equals(previous.symbol()) ? previous.cursor() : null;
        Cursor seed = this.cursor;
        this.starter = () -> factory.start(seed);
        this.expectedSymbol = symbol.value();
        cursorRequired = true;
    }

    public Cursor cursor() { return cursor; }
    public Checkpoint checkpoint() { return new Checkpoint(expectedSymbol,cursor); }

    Mt5TradeStreamClient(ProcessStarter starter) {
        this(starter, null);
    }

    Mt5TradeStreamClient(ProcessStarter starter, Cursor resume) {
        this.starter = starter;
        this.expectedSymbol = null; // Unbound legacy/test adapters; production always binds the live contract.
        this.cursor = resume;
    }

    /** Blocks and delivers every NDJSON trade in source order. */
    public void start(Consumer<MarketTrade> consumer) throws IOException, InterruptedException {
        start(consumer, time -> {});
    }

    public void start(Consumer<MarketTrade> consumer, Consumer<Long> invalidVolume) throws IOException, InterruptedException {
        start(consumer, invalidVolume, (available, time) -> {});
    }

    public void start(Consumer<MarketTrade> consumer, Consumer<Long> invalidVolume,
                      java.util.function.BiConsumer<Boolean, Long> status) throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        startWithCursor((trade, identity) -> consumer.accept(trade), invalidVolume, status);
    }

    /** Additional read-only subscriber metadata; acquisition and filtering are unchanged. */
    public void startWithCursor(java.util.function.BiConsumer<MarketTrade, Cursor> consumer,
                                Consumer<Long> invalidVolume,
                                java.util.function.BiConsumer<Boolean, Long> status) throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        final Process running;
        synchronized (this) {
            if (started || stopping) {
                throw new IllegalStateException("Trade stream ja iniciado ou encerrado; crie outro cliente para reiniciar.");
            }
            started = true;
            process = starter.start();
            running = process;
        }
        CompletableFuture<String> stderr = new CompletableFuture<>();
        Thread.ofVirtual().name("mt5-trade-stream-stderr").start(() -> drainStderr(running, stderr));
        try (var reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
            while (!stopping) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException ex) {
                    if (stopping) break;
                    throw ex;
                }
                if (line == null) break;
                if (!stopping) {
                    com.fasterxml.jackson.databind.JsonNode node;
                    Cursor nextCursor;
                    try {
                        node = MAPPER.readTree(line);
                        nextCursor = parseCursor(node);
                        if (expectedSymbol != null && !matchesSymbol(node.path("symbol").asText()))
                            throw new IOException("Message symbol differs from capture session");
                    } catch (IOException ex) {
                        parseErrors.incrementAndGet();
                        throw ex;
                    }
                    if (nextCursor != null && cursor != null && (nextCursor.timeMsc() < cursor.timeMsc()
                            || nextCursor.timeMsc() == cursor.timeMsc() && nextCursor.ordinal() <= cursor.ordinal())) continue;
                    if ("trade_status".equals(node.path("type").asText())) {
                        if (!node.path("available").isBoolean() || !node.path("timeMsc").isIntegralNumber()
                                || !node.path("timeMsc").canConvertToLong() || node.path("timeMsc").longValue() < 0
                                || !matchesSymbol(node.path("symbol").asText())) throw new IOException("Invalid status protocol");
                        status.accept(node.get("available").booleanValue(), node.get("timeMsc").longValue());
                        continue;
                    }
                    if ("invalid_trade".equals(node.path("type").asText())) {
                        if (nextCursor == null || !matchesSymbol(node.path("symbol").asText())
                                || !"INVALID_VOLUME".equals(node.path("reason").asText())) throw new IOException("Invalid volume protocol");
                        invalidVolume.accept(nextCursor.timeMsc());
                        cursor = nextCursor;
                        continue;
                    }
                    if (cursorRequired && nextCursor == null) throw new IOException("Monitoring trade requires source cursor");
                    MarketTrade trade;
                    try {
                        trade = parseLine(line);
                        if (!matchesSymbol(trade.symbol())) throw new IOException("Trade symbol differs from capture session");
                    } catch (IOException ex) {
                        parseErrors.incrementAndGet();
                        throw ex;
                    }
                    long previous = lastTradeTimeMsc.get();
                    if (previous > trade.timeMsc()) {
                        outOfOrderErrors.incrementAndGet();
                        throw new IOException("Trade stream fora de ordem: " + trade.timeMsc() + " < " + previous);
                    }
                    tradesReceived.incrementAndGet();
                    switch (trade.side()) {
                        case BUY -> buyTrades.incrementAndGet();
                        case SELL -> sellTrades.incrementAndGet();
                        case AMBIGUOUS -> ambiguousTrades.incrementAndGet();
                    }
                    lastTradeTimeMsc.set(trade.timeMsc());
                    consumer.accept(trade, nextCursor);
                    if (nextCursor != null) cursor = nextCursor;
                }
            }
            if (!stopping) {
                if (!running.waitFor(1, TimeUnit.SECONDS)) {
                    throw new IOException("Python fechou stdout inesperadamente e permaneceu ativo.");
                }
                String error;
                try {
                    error = stderr.get();
                } catch (ExecutionException ex) {
                    throw new IOException("Falha ao ler stderr do trade stream MT5.", ex.getCause());
                }
                throw new IOException("Trade stream Python terminou inesperadamente (exit code "
                        + running.exitValue() + "). stderr: " + error);
            }
        } finally {
            stop();
        }
    }

    static MarketTrade parseLine(String line) throws IOException {
        if (line == null || line.isBlank()) {
            throw new IOException("Linha JSON de trade vazia.");
        }
        try {
            var node = MAPPER.readTree(line);
            if (node == null || !node.isObject()) throw new IOException("Invalid trade object");
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            boolean monitoringMessage = object.has("cursorOrdinal") || object.has("volumeSource");
            var flags = object.remove("flags");
            object.remove("cursorOrdinal");
            var volumeSource = object.remove("volumeSource");
            if (monitoringMessage && volumeSource == null) throw new IOException("Missing volume provenance");
            if (volumeSource != null && !"MT5_VOLUME_REAL".equals(volumeSource.asText())) throw new IOException("Unvalidated volume source");
            Mt5TradeMessage message = MAPPER.treeToValue(object, Mt5TradeMessage.class);
            if (message == null) throw new IOException("Mensagem de trade nula.");
            var trade = message.toMarketTrade();
            if (flags != null) {
                if (!flags.isIntegralNumber() || !flags.canConvertToInt()) throw new IOException("Invalid flags");
                var classified = Mt5AggressorMapper.side(flags.intValue());
                if (classified != trade.side()) classified = AggressorSide.AMBIGUOUS;
                return new MarketTrade(trade.symbol(), trade.timeMsc(), trade.price(), trade.volume(), classified);
            }
            if (monitoringMessage) return new MarketTrade(trade.symbol(), trade.timeMsc(), trade.price(), trade.volume(), AggressorSide.AMBIGUOUS);
            return trade;
        } catch (IOException ex) {
            throw new IOException("JSON de trade MT5 invalido.", ex);
        } catch (RuntimeException ex) {
            throw new IOException("Trade MT5 invalido.", ex);
        }
    }

    public Mt5TradeStreamStats stats() {
        return new Mt5TradeStreamStats(
                tradesReceived.get(), buyTrades.get(), sellTrades.get(), ambiguousTrades.get(),
                parseErrors.get(), outOfOrderErrors.get(), lastTradeTimeMsc.get());
    }

    private void drainStderr(Process running, CompletableFuture<String> result) {
        StringBuilder tail = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.log(System.Logger.Level.WARNING, "MT5 trade stream Python: " + line);
                tail.append(line).append('\n');
                if (tail.length() > 8192) tail.delete(0, tail.length() - 8192);
            }
            result.complete(tail.toString());
        } catch (IOException ex) {
            if (stopping) result.complete(tail.toString());
            else result.completeExceptionally(ex);
        }
    }

    /** Signals EOF on stdin and forcibly terminates only after a bounded wait. */
    public synchronized void stop() throws IOException {
        stopping = true;
        if (process == null) return;
        Process running = process;
        try {
            try {
                running.getOutputStream().close();
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Falha ao sinalizar parada do trade stream Python.", ex);
            }
            if (running.isAlive() && !running.waitFor(3, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Trade stream Python nao encerrou em 3s; forcando encerramento.");
                running.destroyForcibly();
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    throw new IOException("Nao foi possivel encerrar o trade stream Python.");
                }
            }
        } catch (InterruptedException ex) {
            running.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Parada do trade stream interrompida; encerramento forcado solicitado.", ex);
        } finally {
            try {
                running.getInputStream().close();
            } finally {
                running.getErrorStream().close();
            }
            if (!running.isAlive()) process = null;
        }
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    private boolean matchesSymbol(String symbol) {
        return expectedSymbol == null ? symbol.matches("WIN[A-Z][0-9]{2}") : expectedSymbol.equals(symbol);
    }

    private static Process startPython(Mt5Symbol symbol, Cursor resume) throws IOException {
        Path script = locateScript();
        ProcessBuilder builder = new ProcessBuilder("python", "-u", script.toString());
        symbol.configure(builder);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("MT5_TRADE_MONITORING_PROTOCOL", "1");
        for (String key : new String[]{"MT5_TRADE_CURSOR_MSC","MT5_TRADE_CURSOR_ORDINAL","MT5_TRADE_CURSOR_SYMBOL"})
            builder.environment().remove(key);
        if (resume != null) {
            builder.environment().put("MT5_TRADE_CURSOR_MSC", Long.toString(resume.timeMsc()));
            builder.environment().put("MT5_TRADE_CURSOR_ORDINAL", Long.toString(resume.ordinal()));
            builder.environment().put("MT5_TRADE_CURSOR_SYMBOL", symbol.value());
        }
        try {
            return builder.start();
        } catch (IOException ex) {
            throw new IOException("Nao foi possivel executar 'python'. Verifique a instalacao e o PATH.", ex);
        }
    }

    private static Path locateScript() throws IOException {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            for (String relative : new String[]{"scripts/mt5_trade_stream.py", "panic-scanner/scripts/mt5_trade_stream.py"}) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("mt5_trade_stream.py nao encontrado; execute dentro do repositorio.");
    }

    private static Cursor parseCursor(com.fasterxml.jackson.databind.JsonNode node) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("Invalid stream object");
        if (!node.has("cursorOrdinal")) return null; // Legacy diagnostic and historical protocol.
        if (!node.path("timeMsc").isIntegralNumber() || !node.path("timeMsc").canConvertToLong()
                || !node.path("cursorOrdinal").isIntegralNumber() || !node.path("cursorOrdinal").canConvertToLong()
                || node.path("timeMsc").longValue() < 0 || node.path("cursorOrdinal").longValue() < 1) {
            throw new IOException("Invalid trade cursor");
        }
        return new Cursor(node.path("timeMsc").longValue(), node.path("cursorOrdinal").longValue());
    }
}
