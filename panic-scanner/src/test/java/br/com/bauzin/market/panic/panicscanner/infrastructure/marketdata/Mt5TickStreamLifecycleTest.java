package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class Mt5TickStreamLifecycleTest {
    @Test
    void springStartsOneSharedProcessAndStopsItOnContextClose() throws Exception {
        var ticks = new CountDownLatch(2);
        var launches = new AtomicInteger();
        var child = new AtomicReference<Process>();
        var handler = new Mt5TickWebSocketHandler(new ObjectMapper(), new Ta4jMt5Sma9Adapter()) {
            @Override public synchronized void broadcast(Mt5Tick tick) { ticks.countDown(); }
        };
        var lifecycle = new Mt5TickStreamLifecycle(handler, () -> new Mt5TickStreamClient(() -> {
            launches.incrementAndGet();
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), Mt5TickStreamClientTest.Fixture.class.getName(), "stream").start();
            child.set(process);
            return process;
        }));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Mt5TickStreamLifecycle.class, () -> lifecycle);
            context.refresh();
            assertTrue(ticks.await(10, TimeUnit.SECONDS));
            lifecycle.start();
            assertEquals(1, launches.get());
            assertTrue(child.get().isAlive());
        }
        assertFalse(child.get().isAlive());
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void feedFailureDoesNotCloseSpringContext() throws Exception {
        var unavailable = new CountDownLatch(1);
        var handler = new Mt5TickWebSocketHandler(new ObjectMapper(), new Ta4jMt5Sma9Adapter()) {
            @Override public synchronized void feedUnavailable() { unavailable.countDown(); }
        };
        var lifecycle = new Mt5TickStreamLifecycle(handler, () -> new Mt5TickStreamClient(() -> {
            throw new IOException("test Python unavailable");
        }));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Mt5TickStreamLifecycle.class, () -> lifecycle);
            context.refresh();
            assertTrue(unavailable.await(5, TimeUnit.SECONDS));
            assertTrue(context.isActive());
            assertFalse(lifecycle.isRunning());
        }
    }
}
