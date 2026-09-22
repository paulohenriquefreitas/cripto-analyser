package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mt5TradeStreamClientTest {
    static final String BUY = """
            {"type":"trade","symbol":"WINV26","timeMsc":1789991940389,
             "price":188375.0,"volume":2.0,"side":"BUY"}
            """.replace("\n", "");

    @Test
    void parsesProtocolDirectlyToDomainMarketTrade() throws Exception {
        assertEquals(new MarketTrade("WINV26", 1789991940389L, 188375, 2, AggressorSide.BUY),
                Mt5TradeStreamClient.parseLine(BUY));
        assertEquals(AggressorSide.AMBIGUOUS,
                Mt5TradeStreamClient.parseLine(BUY.replace("BUY", "AMBIGUOUS")).side());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "null", "{}", "[]", "not JSON",
            "{\"type\":\"quote\",\"symbol\":\"WINV26\",\"timeMsc\":1,\"price\":1,\"volume\":1,\"side\":\"BUY\"}",
            "{\"type\":\"trade\",\"symbol\":\"WINV26\",\"timeMsc\":1,\"price\":1,\"volume\":1,\"side\":\"UNKNOWN\"}"
    })
    void rejectsInvalidProtocol(String line) {
        assertThrows(IOException.class, () -> Mt5TradeStreamClient.parseLine(line));
    }

    @Test
    void rejectsTrailingJsonAndInvalidDomainValues() {
        assertThrows(IOException.class, () -> Mt5TradeStreamClient.parseLine(BUY + " {}"));
        assertThrows(IOException.class, () -> Mt5TradeStreamClient.parseLine(BUY.replace("188375.0", "1e999")));
        assertThrows(IOException.class, () -> Mt5TradeStreamClient.parseLine(BUY.replace("2.0", "0")));
    }

    @Test
    void persistentProcessFeedsEngineWithoutLosingSameMillisecondTrades() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        AtomicReference<Process> child = new AtomicReference<>();
        Mt5TradeStreamClient client = new Mt5TradeStreamClient(() -> {
            launches.incrementAndGet();
            Process process = fixture("stream");
            child.set(process);
            return process;
        });
        TradeFlowEngine engine = new TradeFlowEngine();
        CopyOnWriteArrayList<MarketTrade> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(4);
        try (client; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> {
                client.start(trade -> {
                    received.add(trade);
                    engine.onTrade(trade);
                    delivered.countDown();
                });
                return null;
            });
            try {
                assertTrue(delivered.await(10, TimeUnit.SECONDS));
                assertTrue(child.get().isAlive());
                assertThrows(IllegalStateException.class, () -> client.start(trade -> {}));
                assertEquals(1, launches.get());
            } finally {
                client.stop();
            }
            task.get(10, TimeUnit.SECONDS);
        }

        assertEquals(4, received.size());
        assertTrue(received.stream().allMatch(trade -> trade.timeMsc() == 1789991940389L));
        var snapshot = engine.snapshot(TradeFlowEngine.ONE_SECOND);
        assertEquals(4, snapshot.tradeCount());
        assertEquals(7, snapshot.buyVolume());
        assertEquals(2, snapshot.sellVolume());
        assertEquals(0, snapshot.ambiguousVolume());
        assertEquals(new Mt5TradeStreamStats(4, 3, 1, 0, 0, 0, 1789991940389L), client.stats());
        assertFalse(child.get().isAlive());
    }

    @Test
    void unexpectedExitReportsCodeAndPythonStderr() throws Exception {
        try (var client = new Mt5TradeStreamClient(() -> fixture("error"))) {
            IOException error = assertThrows(IOException.class, () -> client.start(trade -> {}));
            assertTrue(error.getMessage().contains("exit code 7"));
            assertTrue(error.getMessage().contains("copy_ticks_from failed"));
        }
    }

    @Test
    void malformedLineStopsChild() throws Exception {
        Process child = fixture("invalid");
        try (var client = new Mt5TradeStreamClient(() -> child)) {
            assertThrows(IOException.class, () -> client.start(trade -> {}));
            assertEquals(1, client.stats().parseErrors());
            assertFalse(child.isAlive());
        }
    }

    @Test
    void detectsOutOfOrderProtocolBeforeItReachesConsumer() throws Exception {
        Process child = fixture("out-of-order");
        CopyOnWriteArrayList<MarketTrade> received = new CopyOnWriteArrayList<>();
        try (var client = new Mt5TradeStreamClient(() -> child)) {
            IOException error = assertThrows(IOException.class, () -> client.start(received::add));
            assertTrue(error.getMessage().contains("fora de ordem"));
            assertEquals(1, received.size());
            assertEquals(1, client.stats().outOfOrderErrors());
            assertFalse(child.isAlive());
        }
    }

    @Test
    void consumerFailureStopsChildAndPropagates() throws Exception {
        Process child = fixture("stream");
        try (var client = new Mt5TradeStreamClient(() -> child)) {
            assertThrows(IllegalArgumentException.class, () -> client.start(trade -> {
                throw new IllegalArgumentException("consumer failed");
            }));
            assertFalse(child.isAlive());
        }
    }

    private static Process fixture(String mode) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode).start();
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "error" -> {
                    System.err.println("copy_ticks_from failed");
                    System.exit(7);
                }
                case "invalid" -> System.out.println("not JSON");
                case "out-of-order" -> {
                    System.out.println(BUY);
                    System.out.println(BUY.replace("1789991940389", "1789991940388"));
                }
                default -> {
                    System.out.println(BUY.replace("\"volume\":2.0", "\"volume\":1.0"));
                    System.out.println(BUY.replace("\"volume\":2.0", "\"volume\":1.0"));
                    System.out.println(BUY.replace("\"volume\":2.0,\"side\":\"BUY\"",
                            "\"volume\":2.0,\"side\":\"SELL\""));
                    System.out.println(BUY.replace("\"volume\":2.0", "\"volume\":5.0"));
                }
            }
            System.out.flush();
            while (System.in.read() != -1) { }
        }
    }
}
