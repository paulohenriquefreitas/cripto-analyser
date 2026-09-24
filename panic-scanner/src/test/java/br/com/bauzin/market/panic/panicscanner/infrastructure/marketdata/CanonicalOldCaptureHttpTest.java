package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalOldCaptureHttpTest {
    @Test
    void incompleteResponseBodyMustRespectTwoSecondDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                release.await(); // peer sends headers but never finishes JSON
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var method = CanonicalLiveShadowDiagnostics.class.getDeclaredMethod("call", HttpClient.class, URI.class, String.class, String.class);
            method.setAccessible(true);
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null, client,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "GET", "token"));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
                assertEquals(0, headersSent.getCount());
            });
        } finally { release.countDown(); server.stop(0); }
    }
    @Test
    void completeResponseAndExpiredCaptureKeepTheirSemantics() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"token\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 200 : 410, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var method = CanonicalLiveShadowDiagnostics.class.getDeclaredMethod("call", HttpClient.class, URI.class, String.class, String.class);
            method.setAccessible(true);
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var result = (com.fasterxml.jackson.databind.JsonNode) method.invoke(null, client, uri, "POST", null);
            assertEquals("test", result.path("token").asText());
            var failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null, client, uri, "GET", "expired"));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("HTTP 410"));
        } finally { server.stop(0); }
    }

    @Test
    void operationTimingRecordsFailureAsWellAsSuccess() throws Exception {
        var timing = new CanonicalLiveShadowDiagnostics.OperationTiming();
        assertEquals("ok", timing.measure("oldCaptureGET", () -> "ok"));
        assertThrows(IllegalStateException.class, () -> timing.measure("oldCaptureGET", () -> { throw new IllegalStateException(); }));
        var metric = (java.util.Map<?, ?>) timing.metrics().get("oldCaptureGET");
        assertEquals(2L, metric.get("calls"));
        assertTrue((double) metric.get("totalMillis") >= (double) metric.get("maxMillis"));
    }

}
