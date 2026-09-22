package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Standalone proof of communication; deliberately not a Spring component.
 */
public class Mt5ProcessClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public List<Mt5Candle> readCandles() throws IOException, InterruptedException {
        return parseResponse(0, execute(), "");
    }

    Mt5Tick readTick() throws IOException, InterruptedException {
        Mt5Tick tick = MAPPER.readValue(execute("--tick"), Mt5Tick.class);
        if (tick == null) {
            throw new IOException("Python retornou tick nulo.");
        }
        return tick;
    }

    private String execute(String... arguments) throws IOException, InterruptedException {
        Path script = locateScript();
        var command = new ArrayList<>(List.of("python", script.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new IOException("Nao foi possivel executar 'python'. Verifique a instalacao e o PATH.", ex);
        }

        // Drain both pipes concurrently so stderr cannot block the Python process.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = executor.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            var stderr = executor.submit(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    throw new IOException("A consulta ao MT5 excedeu 60 segundos.");
                }
                String output = stdout.get().strip();
                String error = stderr.get().strip();
                checkResponse(process.exitValue(), output, error);
                return output;
            } catch (ExecutionException ex) {
                throw new IOException("Falha ao capturar a resposta do Python.", ex.getCause());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private static Path locateScript() throws IOException {
        // Accept the repository root, module root, or a directory beneath them.
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            for (Path candidate : new Path[]{
                    directory.resolve("scripts/mt5_reader.py"),
                    directory.resolve("panic-scanner/scripts/mt5_reader.py")}) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IOException("Script mt5_reader.py nao encontrado. Execute a partir da raiz do repositorio ou de panic-scanner.");
    }

    private static void checkResponse(int exitCode, String stdout, String stderr) throws IOException {
        if (exitCode != 0) {
            throw new IOException("Python retornou exit code " + exitCode + ": " + stderr);
        }
        if (stdout == null || stdout.isBlank()) {
            throw new IOException("Python retornou resposta vazia. stderr: " + stderr);
        }
    }

    static List<Mt5Candle> parseResponse(int exitCode, String stdout, String stderr) throws IOException {
        checkResponse(exitCode, stdout, stderr);
        List<Mt5Candle> candles;
        try {
            candles = MAPPER.readValue(stdout, new TypeReference<List<Mt5Candle>>() {
            });
        } catch (IOException ex) {
            throw new IOException("Python retornou JSON de candles invalido. stderr: " + stderr, ex);
        }
        if (candles == null || candles.isEmpty()) {
            throw new IOException("Python retornou uma lista de candles nula ou vazia.");
        }
        Long previousTime = null;
        for (int i = 0; i < candles.size(); i++) {
            Mt5Candle candle = candles.get(i);
            if (candle == null || !Double.isFinite(candle.open()) || !Double.isFinite(candle.high())
                    || !Double.isFinite(candle.low()) || !Double.isFinite(candle.close())
                    || candle.high() < candle.low() || candle.open() <= 0 || candle.close() <= 0) {
                throw new IOException("Candle invalido no indice " + i + ": " + candle);
            }
            if (previousTime != null && candle.time() <= previousTime) {
                throw new IOException("Timestamps fora de ordem crescente no indice " + i);
            }
            previousTime = candle.time();
        }
        return List.copyOf(candles);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("MT5 candles test");
        List<Mt5Candle> candles = new Mt5ProcessClient().readCandles();
        System.out.println("\nCandles recebidos: " + candles.size());
        printCandle("Primeiro candle", candles.getFirst());
        printCandle("\u00daltimo candle", candles.getLast());
    }

    private static void printCandle(String label, Mt5Candle candle) {
        var formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
                .withZone(ZoneId.of("America/Sao_Paulo"));
        System.out.println("\n" + label + ":");
        System.out.println(formatter.format(Instant.ofEpochSecond(candle.time())));
        System.out.println("O: " + candle.open());
        System.out.println("H: " + candle.high());
        System.out.println("L: " + candle.low());
        System.out.println("C: " + candle.close());
        System.out.println("Volume real: " + candle.realVolume());
        System.out.println("Volume de ticks: " + candle.tickVolume());
    }
}
