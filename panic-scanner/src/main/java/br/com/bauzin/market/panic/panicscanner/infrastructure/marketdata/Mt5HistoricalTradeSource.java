package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplaySource;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;

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
import java.util.function.Consumer;

/** Streaming NDJSON adapter from MT5 history to normalized MarketTrade. */
public final class Mt5HistoricalTradeSource implements ReplaySource<MarketTrade> {
    private static final String VALIDATED_SYMBOL = "WINV26";
    private final ReplaySession session;
    private final ProcessStarter starter;
    private Process process;
    private boolean started;

    @FunctionalInterface
    interface ProcessStarter { Process start() throws IOException; }

    public Mt5HistoricalTradeSource(ReplaySession session) {
        this(session, () -> startPython(session));
    }

    Mt5HistoricalTradeSource(ReplaySession session, ProcessStarter starter) {
        this.session = Objects.requireNonNull(session, "session");
        if (!VALIDATED_SYMBOL.equals(session.symbol())) {
            throw new IllegalArgumentException("Historical aggressor replay is validated only for "
                    + VALIDATED_SYMBOL + "; continuous symbols such as WIN$N are forbidden.");
        }
        this.starter = Objects.requireNonNull(starter, "starter");
    }

    @Override
    public void stream(Consumer<MarketTrade> consumer) throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (this) {
            if (started) throw new IllegalStateException("Historical source is single-use.");
            started = true;
            process = starter.start();
        }
        Process running = process;
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readStderr(running));
        try (var reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.interrupted()) throw new InterruptedException("Replay interrupted.");
                consumer.accept(Mt5TradeStreamClient.parseLine(line));
            }
            if (!running.waitFor(30, TimeUnit.SECONDS))
                throw new IOException("Historical MT5 process did not finish after stdout closed.");
            String error = await(stderr);
            if (running.exitValue() != 0)
                throw new IOException("Historical MT5 process failed with exit code "
                        + running.exitValue() + ". stderr: " + error);
        } finally {
            close();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (process == null) return;
        Process running = process;
        if (running.isAlive()) {
            running.destroy();
            try {
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                    running.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                running.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping historical MT5 process.", ex);
            }
        }
        running.getInputStream().close();
        running.getErrorStream().close();
        running.getOutputStream().close();
        process = null;
    }

    private static Process startPython(ReplaySession session) throws IOException {
        Path script = locateScript();
        ProcessBuilder builder = new ProcessBuilder("python", "-u", script.toString(),
                "--symbol", session.symbol(),
                "--start-msc", Long.toString(session.loadStartTimeMsc()),
                "--end-msc", Long.toString(session.analysisEndTimeMsc()));
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        return builder.start();
    }

    private static Path locateScript() throws IOException {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            for (String relative : new String[]{"scripts/mt5_trade_history.py",
                    "panic-scanner/scripts/mt5_trade_history.py"}) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("mt5_trade_history.py not found; run inside the repository.");
    }

    private static String readStderr(Process process) {
        try {
            return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "Could not read stderr: " + ex.getMessage();
        }
    }

    private static String await(CompletableFuture<String> future) throws IOException, InterruptedException {
        try { return future.get(); }
        catch (ExecutionException ex) { throw new IOException("Could not collect historical stderr.", ex.getCause()); }
    }
}
