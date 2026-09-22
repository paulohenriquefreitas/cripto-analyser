package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

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
import java.util.function.Consumer;

/** Single-use, blocking stream. Call stop from another thread (or the consumer). */
public final class Mt5TickStreamClient implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Mt5TickStreamClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private final ProcessStarter starter;
    private Process process;
    private boolean started;
    private volatile boolean stopping;

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    public Mt5TickStreamClient() {
        this(Mt5TickStreamClient::startPython);
    }

    Mt5TickStreamClient(ProcessStarter starter) {
        this.starter = starter;
    }

    /** Blocks while delivering ticks in order. IO/process/consumer failures propagate to the caller. */
    public void start(Consumer<Mt5Tick> consumer) throws IOException, InterruptedException {
        Objects.requireNonNull(consumer, "consumer");
        final Process running;
        synchronized (this) {
            if (started || stopping) {
                throw new IllegalStateException("Stream ja iniciado ou encerrado; crie outro cliente para reiniciar.");
            }
            started = true;
            process = starter.start();
            running = process;
        }
        CompletableFuture<String> stderr = new CompletableFuture<>();
        Thread.ofVirtual().name("mt5-stream-stderr").start(() -> drainStderr(running, stderr));
        try (var reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
            while (!stopping) {
                String line;
                try {
                    line = reader.readLine();
                } catch (IOException ex) {
                    if (stopping) break; // Stream closed by an explicit stop.
                    throw ex;
                }
                if (line == null) break;
                if (!stopping) consumer.accept(parseLine(line));
            }
            if (!stopping) {
                if (!running.waitFor(1, TimeUnit.SECONDS)) {
                    throw new IOException("Python fechou stdout inesperadamente e permaneceu ativo.");
                }
                String error;
                try {
                    error = stderr.get();
                } catch (ExecutionException ex) {
                    throw new IOException("Falha ao ler stderr do stream MT5.", ex.getCause());
                }
                throw new IOException("Stream Python terminou inesperadamente (exit code "
                        + running.exitValue() + "). stderr: " + error);
            }
        } finally {
            stop();
        }
    }

    static Mt5Tick parseLine(String line) throws IOException {
        if (line == null || line.isBlank()) throw new IOException("Linha JSON de tick vazia.");
        final Mt5Tick tick;
        try {
            tick = MAPPER.readValue(line, Mt5Tick.class);
        } catch (IOException ex) {
            throw new IOException("JSON de tick MT5 invalido.", ex);
        }
        if (tick == null || !Double.isFinite(tick.bid()) || !Double.isFinite(tick.ask())
                || !Double.isFinite(tick.last()) || !Double.isFinite(tick.volume())) {
            throw new IOException("Tick MT5 nulo ou com valores nao finitos.");
        }
        return tick;
    }

    private void drainStderr(Process running, CompletableFuture<String> result) {
        StringBuilder tail = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.log(System.Logger.Level.WARNING, "MT5 Python: " + line);
                tail.append(line).append('\n');
                if (tail.length() > 8192) tail.delete(0, tail.length() - 8192);
            }
            result.complete(tail.toString());
        } catch (IOException ex) {
            if (stopping) result.complete(tail.toString());
            else result.completeExceptionally(ex);
        }
    }

    /** EOF on stdin asks Python to run its finally; force termination is a bounded fallback. */
    public synchronized void stop() throws IOException {
        stopping = true;
        if (process == null) return;
        Process running = process;
        try {
            try {
                running.getOutputStream().close();
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Falha ao sinalizar parada do Python.", ex);
            }
            if (running.isAlive() && !running.waitFor(3, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Python nao encerrou em 3s; forcando encerramento.");
                running.destroyForcibly();
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    throw new IOException("Nao foi possivel encerrar o processo Python.");
                }
            }
        } catch (InterruptedException ex) {
            running.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Parada do stream interrompida; encerramento forcado solicitado.", ex);
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
        Path script = null;
        for (Path directory = Path.of("").toAbsolutePath(); directory != null && script == null; directory = directory.getParent()) {
            for (String relative : new String[] {"scripts/mt5_tick_stream.py", "panic-scanner/scripts/mt5_tick_stream.py"}) {
                Path candidate = directory.resolve(relative);
                if (Files.isRegularFile(candidate)) { script = candidate; break; }
            }
        }
        if (script == null) throw new IOException("mt5_tick_stream.py nao encontrado; execute dentro do repositorio.");
        var builder = new ProcessBuilder("python", "-u", script.toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        try {
            return builder.start();
        } catch (IOException ex) {
            throw new IOException("Nao foi possivel executar 'python'. Verifique a instalacao e o PATH.", ex);
        }
    }
}
