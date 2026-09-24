package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalQueueTest {
    @Test void burstLargerThanCapacityWaitsThenPreservesEveryMessageInOrder() throws Exception {
        var queue = new CanonicalLiveShadowDiagnostics.CanonicalQueue();
        int capacity = CanonicalLiveShadowDiagnostics.QUEUE_CAPACITY;
        // Distinct transport records with identical canonical events: no deduplication.
        var messages = new Mt5CanonicalPriceStream.Message[capacity + 3];
        for (int i = 0; i < messages.length; i++)
            messages[i] = Mt5CanonicalPriceStream.parse(Mt5CanonicalPriceStreamTest.PRICE);
        var full = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < messages.length; i++) {
                    if (i == capacity) full.countDown();
                    queue.enqueue(messages[i]);
                }
            } catch (Throwable ex) { failure.set(ex); }
        });
        try {
            assertTrue(full.await(5, TimeUnit.SECONDS));
            // Consumer is deliberately stalled, as it can be during synchronous IO.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (producer.isAlive() && producer.getState() != Thread.State.WAITING
                    && System.nanoTime() < deadline) Thread.yield();
            assertNull(failure.get(), "A bounded burst must backpressure, not abort the source");
            assertTrue(producer.isAlive(), "Producer must wait for the consumer");
            assertEquals(capacity, queue.size());
            assertEquals(capacity, queue.metrics().get("queueCapacity"));
            assertEquals((long) capacity, queue.metrics().get("maxQueueSize"));
            assertTrue((Long) queue.metrics().get("overflowCount") >= 1);
            for (var expected : messages) {
                var received = queue.poll(5, TimeUnit.SECONDS);
                assertNotNull(received);
                assertSame(expected, received.message(), "Preserve order and every identical event");
            }
            producer.join(5000);
            assertFalse(producer.isAlive());
            assertNull(failure.get());
            assertTrue(queue.isEmpty());
            assertTrue((Double) queue.metrics().get("producerBlockedMillis") > 0);
        } finally {
            producer.interrupt(); producer.join(5000);
        }
    }
    @Test void queueWithoutSaturationHasNoOverflowOrWait() throws Exception {
        var queue = new CanonicalLiveShadowDiagnostics.CanonicalQueue();
        var message = Mt5CanonicalPriceStream.parse(Mt5CanonicalPriceStreamTest.PRICE);
        queue.enqueue(message);
        assertEquals(1L, queue.metrics().get("maxQueueSize"));
        assertEquals(0L, queue.metrics().get("overflowCount"));
        assertEquals(0.0, queue.metrics().get("producerBlockedMillis"));
        assertSame(message, queue.take().message());
    }

    @Test void interruptReleasesBlockedProducerAndKeepsAcceptedMessages() throws Exception {
        var queue = new CanonicalLiveShadowDiagnostics.CanonicalQueue();
        var message = Mt5CanonicalPriceStream.parse(Mt5CanonicalPriceStreamTest.PRICE);
        for (int i = 0; i < CanonicalLiveShadowDiagnostics.QUEUE_CAPACITY; i++) queue.enqueue(message);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread producer = Thread.ofVirtual().start(() -> {
            try { queue.enqueue(message); }
            catch (Throwable ex) { failure.set(ex); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            awaitSaturation(queue);
            producer.interrupt(); producer.join(5000);
            assertFalse(producer.isAlive());
            assertInstanceOf(CancellationException.class, failure.get());
            assertTrue(interrupted.get());
            assertEquals(CanonicalLiveShadowDiagnostics.QUEUE_CAPACITY, queue.size());
            assertEquals(1L, queue.metrics().get("overflowCount"));
        } finally { producer.interrupt(); producer.join(5000); }
    }

    static void awaitSaturation(CanonicalLiveShadowDiagnostics.CanonicalQueue queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((Long) queue.metrics().get("overflowCount") == 0 && System.nanoTime() < deadline)
            Thread.sleep(1);
        assertTrue((Long) queue.metrics().get("overflowCount") > 0, "Producer must reach the full queue");
    }

    public static class BurstChild {
        public static void main(String[] args) throws Exception {
            for (int i = 0; i <= CanonicalLiveShadowDiagnostics.QUEUE_CAPACITY; i++)
                System.out.println(Mt5CanonicalPriceStreamTest.PRICE);
            System.out.flush();
            System.in.read(); // no MT5: exit when lifecycle closes stdin
        }
    }

    @Test void shutdownWhileRealChildReaderIsBackpressuredLeavesNoOrphan() throws Exception {
        var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), BurstChild.class.getName()).start();
        var source = new Mt5CanonicalPriceStream(() -> child);
        var queue = new CanonicalLiveShadowDiagnostics.CanonicalQueue();
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { source.start(queue::enqueue); } catch (Throwable ex) { failure.set(ex); }
        });
        try {
            awaitSaturation(queue);
            assertTrue(child.isAlive());
            CanonicalLiveShadowDiagnostics.stopSource(source, worker);
            assertFalse(worker.isAlive());
            assertFalse(child.isAlive());
            assertEquals(CanonicalLiveShadowDiagnostics.QUEUE_CAPACITY, queue.size());
        } finally {
            CanonicalLiveShadowDiagnostics.stopSource(source, worker);
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Test void consumerTimingReportsMaximumAndApproximateActiveShare() {
        var timing = new CanonicalLiveShadowDiagnostics.ConsumerTiming();
        timing.record(TimeUnit.MILLISECONDS.toNanos(5));
        timing.record(TimeUnit.MILLISECONDS.toNanos(15));
        var metrics = timing.metrics(TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals(2L, metrics.get("consumerIterations"));
        assertEquals(0.020, metrics.get("consumerActiveSeconds"));
        assertEquals(15.0, metrics.get("maxConsumerIterationMillis"));
        assertEquals(20.0, metrics.get("consumerActivePercentApprox"));
    }
}
