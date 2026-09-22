package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
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
        this(Mt5TradeStreamClient::startPython);
    }

    Mt5TradeStreamClient(ProcessStarter starter) {
        this.starter = starter;
    }

    /** Blocks and delivers every NDJSON trade in source order. */
    public void start(Consumer<MarketTrade> consumer) throws IOException, InterruptedException {
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
                    MarketTrade trade;
                    try {
                        trade = parseLine(line);
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
                    consumer.accept(trade);
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
            Mt5TradeMessage message = MAPPER.readValue(line, Mt5TradeMessage.class);
            if (message == null) throw new IOException("Mensagem de trade nula.");
            return message.toMarketTrade();
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

    private static Process startPython() throws IOException {
        Path script = locateScript();
        ProcessBuilder builder = new ProcessBuilder("python", "-u", script.toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
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
}
