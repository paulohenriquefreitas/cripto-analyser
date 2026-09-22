package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Mt5TickStreamClientTest {
    static final String LINE = """
            {"time":1789991940,"timeMsc":1789991940389,"bid":187635,
             "ask":187640,"last":187640,"volume":4}
            """.replace("\n", "");

    @Test
    void parsesRawTimestampsAndVolumeAndSupportsExistingSnakeCase() throws Exception {
        var expected = new Mt5Tick(1789991940L, 1789991940389L, 187635, 187640, 187640, 4);
        assertEquals(expected, Mt5TickStreamClient.parseLine(LINE));
        assertEquals(expected, Mt5TickStreamClient.parseLine(LINE.replace("timeMsc", "time_msc")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not JSON", "null", "{}", "[]", "{\"time\":null}"})
    void rejectsInvalidJson(String json) {
        assertThrows(IOException.class, () -> Mt5TickStreamClient.parseLine(json));
    }

    @Test
    void rejectsTrailingJsonAndNonfiniteValues() {
        assertThrows(IOException.class, () -> Mt5TickStreamClient.parseLine(LINE + " {}"));
        assertThrows(IOException.class, () -> Mt5TickStreamClient.parseLine(LINE.replace("187635", "1e999")));
    }

    @Test
    void keepsOneProcessDeliversSamePriceTicksAndStopsGracefully() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        AtomicReference<Process> child = new AtomicReference<>();
        var client = new Mt5TickStreamClient(() -> {
            launches.incrementAndGet();
            Process process = fixture("stream");
            child.set(process);
            return process;
        });
        var received = new CopyOnWriteArrayList<Mt5Tick>();
        var delivered = new CountDownLatch(2);
        try (client; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> {
                client.start(tick -> { received.add(tick); delivered.countDown(); });
                return null;
            });
            try {
                assertTrue(delivered.await(10, TimeUnit.SECONDS));
                assertTrue(child.get().isAlive());
                assertThrows(IllegalStateException.class, () -> client.start(tick -> {}));
                assertEquals(1, launches.get());
                assertEquals(2, received.size());
                assertEquals(received.get(0).last(), received.get(1).last());
                assertNotEquals(received.get(0).timeMsc(), received.get(1).timeMsc());
            } finally {
                client.stop();
            }
            task.get(10, TimeUnit.SECONDS);
            assertFalse(child.get().isAlive());
            assertEquals(0, child.get().exitValue());
            client.stop();
        }
    }

    @Test
    void unexpectedExitIncludesCodeAndStderr() throws Exception {
        try (var client = new Mt5TickStreamClient(() -> fixture("error"))) {
            IOException ex = assertThrows(IOException.class, () -> client.start(tick -> {}));
            assertTrue(ex.getMessage().contains("exit code 7"));
            assertTrue(ex.getMessage().contains("initialize failed"));
        }
    }

    @Test
    void unexpectedSuccessfulExitIsStillReported() throws Exception {
        try (var client = new Mt5TickStreamClient(() -> fixture("exit"))) {
            IOException ex = assertThrows(IOException.class, () -> client.start(tick -> {}));
            assertTrue(ex.getMessage().contains("exit code 0"));
        }
    }

    @Test
    void malformedLineTerminatesChild() throws Exception {
        Process child = fixture("invalid");
        try (var client = new Mt5TickStreamClient(() -> child)) {
            assertThrows(IOException.class, () -> client.start(tick -> fail("Invalid tick delivered")));
            assertFalse(child.isAlive());
        }
    }

    @Test
    void consumerFailureTerminatesChild() throws Exception {
        Process child = fixture("stream");
        try (var client = new Mt5TickStreamClient(() -> child)) {
            assertThrows(IllegalArgumentException.class, () -> client.start(tick -> {
                throw new IllegalArgumentException("consumer failed");
            }));
            assertFalse(child.isAlive());
        }
    }

    @Test
    void stopCanBeCalledInsideConsumer() throws Exception {
        Process child = fixture("stream");
        try (var client = new Mt5TickStreamClient(() -> child)) {
            client.start(tick -> {
                try { client.stop(); } catch (IOException ex) { throw new RuntimeException(ex); }
            });
            assertFalse(child.isAlive());
        }
    }

    @Test
    void startupFailurePropagatesAndStopIsSafe() {
        var client = new Mt5TickStreamClient(() -> { throw new IOException("python unavailable"); });
        assertThrows(IOException.class, () -> client.start(tick -> {}));
        assertDoesNotThrow(client::stop);
    }

    private static Process fixture(String mode) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode).start();
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "error" -> { System.err.println("initialize failed"); System.exit(7); }
                case "exit" -> { return; }
                case "invalid" -> System.out.println("not JSON");
                default -> {
                    System.out.println(LINE);
                    System.out.println(LINE.replace("1789991940389", "1789991940520"));
                }
            }
            System.out.flush();
            // Wait for stdin EOF, just like the real Python shutdown protocol.
            while (System.in.read() != -1) { }
        }
    }
}
