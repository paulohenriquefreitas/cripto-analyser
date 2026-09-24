package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Single-use isolated Python child. Failure/stop never touches the application processes. */
public final class Mt5CanonicalPriceStream implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @FunctionalInterface interface Starter { Process start() throws IOException; }
    public record Message(String type, CanonicalPriceEvent event, JsonNode data) {}
    private final Starter starter;
    private Process process;
    private boolean started;
    private volatile boolean stopping;
    private volatile long parseErrors, orderErrors;
    private long previousTime = -1;
    private String symbol;

    public Mt5CanonicalPriceStream(String symbol, int probeSeconds) {
        this(() -> launch(symbol, probeSeconds));
    }
    Mt5CanonicalPriceStream(Starter starter) { this.starter = starter; }

    public void start(Consumer<Message> consumer) throws IOException, InterruptedException {
        Process running;
        synchronized (this) {
            if (started || stopping) throw new IllegalStateException("Create a new source for a new session");
            started = true;
            running = process = starter.start();
        }
        var error = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            StringBuilder tail = new StringBuilder();
            try (var reader = running.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.append(line).append('\n');
                    if (tail.length() > 8192) tail.delete(0, tail.length() - 8192);
                }
                error.complete(tail.toString());
            } catch (IOException ex) { error.complete(tail + " " + ex); }
        });
        boolean inactive = false, ended = false;
        try (var reader = running.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while (!stopping && (line = reader.readLine()) != null) {
                Message message;
                try { message = parse(line); }
                catch (IOException | IllegalArgumentException ex) { parseErrors++; throw new IOException("Invalid canonical NDJSON", ex); }
                if (ended) throw new IOException("Data after stopped trailer");
                if (message.event() != null) {
                    var event = message.event();
                    if (symbol != null && !symbol.equals(event.symbol())) throw new IOException("Mixed canonical symbols");
                    if (event.timeMsc() < previousTime) { orderErrors++; throw new IOException("ORDER_ERROR canonical source"); }
                    symbol = event.symbol(); previousTime = event.timeMsc();
                }
                inactive |= message.type().equals("inactive");
                ended = message.type().equals("stopped");
                consumer.accept(message);
            }
            if (!stopping) {
                if (!running.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Python closed stdout but did not exit");
                String stderr;
                try { stderr = error.get(2, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException ex) { throw new IOException("stderr drain failed", ex); }
                if (running.exitValue() != 0 || !inactive || !ended)
                    throw new IOException("Canonical Python exit code " + running.exitValue() + ": " + stderr);
            }
        } catch (IOException ex) {
            if (!stopping) throw ex;
        } finally { close(); }
    }

    static Message parse(String line) throws IOException {
        JsonNode n = JSON.readTree(line);
        if (n == null || !n.isObject() || !n.path("type").isTextual()) throw new IOException("Missing type");
        String type = n.get("type").textValue();
        if (!Set.of("price", "header", "watermark", "official", "inactive", "stopped").contains(type))
            throw new IOException("Unknown type: " + type);
        CanonicalPriceEvent event = null;
        if (type.equals("price")) {
            if (!n.path("symbol").isTextual() || !n.path("timeMsc").isIntegralNumber()
                    || !n.get("timeMsc").canConvertToLong() || !n.path("price").isNumber()) throw new IOException("Invalid price event");
            event = new CanonicalPriceEvent(n.get("symbol").textValue(), n.get("timeMsc").longValue(), n.get("price").doubleValue());
        }
        return new Message(type, event, n);
    }

    public long parseErrors() { return parseErrors; }
    public long orderErrors() { return orderErrors; }

    @Override public synchronized void close() throws IOException {
        stopping = true;
        if (process == null) return;
        Process child = process;
        try {
            child.getOutputStream().close(); // Python stdin EOF -> finally -> shutdown
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                if (!child.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Canonical child still alive");
            }
        } catch (InterruptedException ex) {
            child.destroyForcibly(); Thread.currentThread().interrupt();
            throw new IOException("Interrupted stopping canonical child", ex);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            child.getInputStream().close(); child.getErrorStream().close();
            if (!child.isAlive()) process = null;
        }
    }

    private static Process launch(String symbol, int probeSeconds) throws IOException {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            for (String relative : new String[]{"panic-scanner/scripts/mt5_canonical_price_stream.py", "scripts/mt5_canonical_price_stream.py"}) {
                Path script = directory.resolve(relative);
                if (Files.isRegularFile(script)) {
                    var builder = new ProcessBuilder("python", "-u", script.toString(), "--symbol", symbol,
                            "--probe-seconds", Integer.toString(probeSeconds));
                    builder.environment().put("PYTHONIOENCODING", "utf-8");
                    return builder.start();
                }
            }
        }
        throw new IOException("mt5_canonical_price_stream.py not found");
    }
}
