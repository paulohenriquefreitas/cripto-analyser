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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
            assertTrue(lifecycle.isRunning()); // Lifecycle stays active while awaiting reconnection.
        }
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void retriesInitialFailureWithNewClientAndOnlyPublishesAfterValidTick() throws Exception {
        var handler = mock(Mt5TickWebSocketHandler.class);
        var delivered = new CountDownLatch(1);
        doAnswer(call -> { delivered.countDown(); return null; }).when(handler).broadcast(any());
        var attempts = new AtomicInteger();
        var ready = new CountDownLatch(1);
        var child = new AtomicReference<Process>();
        var clients = new CopyOnWriteArrayList<Mt5TickStreamClient>();
        var times = new CopyOnWriteArrayList<Long>();
        var lifecycle = new Mt5TickStreamLifecycle(handler, () -> {
            times.add(System.nanoTime());
            int attempt = attempts.incrementAndGet();
            var client = new Mt5TickStreamClient(() -> {
                if (attempt == 1) throw new IOException("initial launch failed");
                var process = gatedProcess();
                child.set(process);
                ready.countDown();
                return process;
            });
            clients.add(client);
            return client;
        });
        try {
            lifecycle.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertNotSame(clients.get(0), clients.get(1));
            assertTrue(times.get(1) - times.get(0) >= TimeUnit.SECONDS.toNanos(1));
            verify(handler, atLeastOnce()).feedUnavailable();
            verify(handler, never()).broadcast(any());
            child.get().getOutputStream().write('t');
            child.get().getOutputStream().flush();
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            verify(handler).broadcast(any(Mt5Tick.class));
            assertTrue(lifecycle.isRunning());
        } finally { lifecycle.stop(); }
        assertFalse(child.get().isAlive());
        assertFalse(lifecycle.isRunning());
        verify(handler, atLeast(2)).feedUnavailable();
    }

    @Test
    void closesFailedProcessBeforeLaunchingReplacementAndStopsActiveAttempt() throws Exception {
        var handler = mock(Mt5TickWebSocketHandler.class);
        var delivered = new CountDownLatch(1);
        doAnswer(call -> { delivered.countDown(); return null; }).when(handler).broadcast(any());
        var children = new CopyOnWriteArrayList<Process>();
        var ready = new CountDownLatch(1);
        var previousClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var lifecycle = new Mt5TickStreamLifecycle(handler, () -> new Mt5TickStreamClient(() -> {
            if (children.isEmpty()) {
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"),
                        Mt5TickStreamClientTest.Fixture.class.getName(), "invalid").start();
                children.add(process);
                return process;
            }
            previousClosed.set(!children.get(0).isAlive());
            var process = gatedProcess();
            children.add(process);
            ready.countDown();
            return process;
        }));
        try {
            lifecycle.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(previousClosed.get());
            verify(handler, never()).broadcast(any());
            children.get(1).getOutputStream().write('t');
            children.get(1).getOutputStream().flush();
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            verify(handler).broadcast(any(Mt5Tick.class));
        } finally { lifecycle.stop(); }
        assertTrue(children.stream().noneMatch(Process::isAlive));
        Thread.sleep(1100);
        assertEquals(2, children.size());
    }

    @Test
    void shutdownInterruptsRetryDelayAndPreventsFurtherAttempts() throws Exception {
        var handler = mock(Mt5TickWebSocketHandler.class);
        var attempts = new AtomicInteger();
        var unavailable = new CountDownLatch(1);
        doAnswer(call -> { unavailable.countDown(); return null; }).when(handler).feedUnavailable();
        var lifecycle = new Mt5TickStreamLifecycle(handler, () -> new Mt5TickStreamClient(() -> {
            attempts.incrementAndGet();
            throw new IOException("unavailable");
        }));
        try {
            lifecycle.start();
            assertTrue(unavailable.await(3, TimeUnit.SECONDS));
        } finally { lifecycle.stop(); }
        Thread.sleep(1100);
        assertEquals(1, attempts.get());
        assertFalse(lifecycle.isRunning());
        verify(handler, never()).broadcast(any());
    }

    private static Process gatedProcess() throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), GatedFixture.class.getName()).start();
    }

    public static class GatedFixture {
        public static void main(String[] args) throws Exception {
            if (System.in.read() == -1) return;
            System.out.println(Mt5TickStreamClientTest.LINE);
            System.out.flush();
            while (System.in.read() != -1) { }
        }
    }
}
