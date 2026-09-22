package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.flow.TradeFlowEngine;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.TradeReplayRunner;
import br.com.bauzin.market.panic.panicscanner.domain.replay.ReplaySession;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class Mt5HistoricalTradeSourceTest {
    private static final ReplaySession SESSION = new ReplaySession("WINV26", 0, 0, 10);
    private static final String TRADE = """
            {"type":"trade","symbol":"WINV26","timeMsc":1,"price":100.0,"volume":2.0,"side":"BUY"}
            """.strip();

    @Test void streamsNdjsonInOrderAndClosesFiniteProcess() throws Exception {
        AtomicReference<Process> child = new AtomicReference<>();
        List<MarketTrade> received = new ArrayList<>();
        try (var source = new Mt5HistoricalTradeSource(SESSION, () -> {
            Process value = fixture("valid"); child.set(value); return value;
        })) {
            source.stream(received::add);
        }
        assertEquals(2, received.size());
        assertEquals(List.of(1L, 1L), received.stream().map(MarketTrade::timeMsc).toList());
        assertFalse(child.get().isAlive());
    }

    @Test void propagatesPythonFailureWithStderr() throws Exception {
        try (var source = new Mt5HistoricalTradeSource(SESSION, () -> fixture("error"))) {
            IOException error = assertThrows(IOException.class, () -> source.stream(trade -> {}));
            assertTrue(error.getMessage().contains("copy_ticks_range failed"));
            assertTrue(error.getMessage().contains("exit code 7"));
        }
    }

    @Test void rejectsInvalidNdjsonAndDoesNotFeedInvalidEventToEngine() throws Exception {
        TradeFlowEngine engine = new TradeFlowEngine();
        try (var source = new Mt5HistoricalTradeSource(SESSION, () -> fixture("invalid"))) {
            assertThrows(IOException.class,
                    () -> new TradeReplayRunner(engine).run(SESSION, source, null));
        }
        assertEquals(1, engine.snapshot(TradeFlowEngine.TEN_SECONDS).tradeCount());
    }

    @Test void consumerFailureTerminatesOwnedProcess() throws Exception {
        AtomicReference<Process> child = new AtomicReference<>();
        try (var source = new Mt5HistoricalTradeSource(SESSION, () -> {
            Process value = fixture("blocking"); child.set(value); return value;
        })) {
            assertThrows(IllegalStateException.class,
                    () -> source.stream(trade -> { throw new IllegalStateException("stop"); }));
        }
        assertFalse(child.get().isAlive());
    }

    @Test void rejectsContinuousOrUnvalidatedSymbol() {
        assertThrows(IllegalArgumentException.class, () -> new Mt5HistoricalTradeSource(
                new ReplaySession("WIN$N", 0, 0, 10)));
    }

    private static Process fixture(String mode) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode).start();
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "error" -> { System.err.println("copy_ticks_range failed"); System.exit(7); }
                case "invalid" -> { System.out.println(TRADE); System.out.println("not-json"); }
                case "blocking" -> {
                    System.out.println(TRADE); System.out.flush(); Thread.sleep(60_000);
                }
                default -> { System.out.println(TRADE); System.out.println(TRADE); }
            }
        }
    }
}
