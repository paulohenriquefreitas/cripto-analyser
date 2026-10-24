package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Mt5TickWebSocketHandlerTest {
    @Test void flowSnapshotsAreSharedAndReplayedToReconnectingClientsWithoutRecounting() throws Exception {
        var flow = new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5MonitoringFlowService();
        var handler = new Mt5TickWebSocketHandler(mapper, new Ta4jMt5Sma9Adapter(),
                new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.MarketStructureLiveObserver(), flow);
        flow.onTrade(new br.com.bauzin.market.panic.panicscanner.domain.win.flow.MarketTrade(
                "WINZ26", System.currentTimeMillis() - 180 * 60_000L, tick.last(), 2,
                br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide.BUY));
        handler.broadcastFlow(flow.snapshot());
        var messages = new CopyOnWriteArrayList<String>();
        var delivered = new CountDownLatch(4);
        try {
            handler.afterConnectionEstablished(session("a", messages, delivered));
            handler.afterConnectionEstablished(session("b", messages, delivered));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var snapshots = messages.stream().filter(text -> text.contains("MT5_VOLUME_REAL")).toList();
            assertEquals(2, snapshots.size());
            for (String text : snapshots) {
                var json = mapper.readTree(text);
                assertEquals(2, json.get("windows").get(0).get("buyVolume").doubleValue());
                assertEquals(1, json.get("windows").get(0).get("events").longValue());
                assertEquals(180, json.get("timeBasis").get("feedTimeShiftMinutes").longValue());
                assertFalse(json.get("timeBasis").get("confirmed").booleanValue());
            }
            var reconnect = new CopyOnWriteArrayList<String>();
            var reconnected = new CountDownLatch(2);
            handler.afterConnectionEstablished(session("reconnected", reconnect, reconnected));
            assertTrue(reconnected.await(5, TimeUnit.SECONDS));
            assertEquals(2, mapper.readTree(reconnect.getLast()).get("windows").get(0).get("buyVolume").doubleValue());
            assertEquals(2, flow.snapshot().windows().getFirst().buyVolume());
        } finally { handler.shutdown(); }
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final Mt5Tick tick = new Mt5Tick(1789994563L, 1789994563184L, 187580, 187585, 187585, 4);

    @Test
    void broadcastsToTwoSessionsWithExactTimestamps() throws Exception {
        var handler = new Mt5TickWebSocketHandler(mapper, new Ta4jMt5Sma9Adapter());
        var messages = new CopyOnWriteArrayList<String>();
        var delivered = new CountDownLatch(4);
        try {
            handler.afterConnectionEstablished(session("a", messages, delivered));
            handler.afterConnectionEstablished(session("b", messages, delivered));
            handler.broadcast(tick);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            List<String> ticks = messages.stream().filter(s -> s.contains("WINZ26")).toList();
            assertEquals(2, ticks.size());
            var json = mapper.readTree(ticks.getFirst());
            assertEquals(tick.time(), json.get("time").longValue());
            assertEquals(tick.timeMsc(), json.get("timeMsc").longValue());
            assertEquals(tick.last(), json.get("last").doubleValue());
            assertEquals(tick.bid(), json.get("bid").doubleValue());
            assertEquals(tick.ask(), json.get("ask").doubleValue());
            assertEquals(tick.volume(), json.get("volume").doubleValue());
            assertFalse(json.has("time_msc"));
        } finally { handler.shutdown(); }
    }

    @Test
    void newConnectionReceivesLatestTickAndFeedFailureStatus() throws Exception {
        var handler = new Mt5TickWebSocketHandler(mapper, new Ta4jMt5Sma9Adapter());
        var messages = new CopyOnWriteArrayList<String>();
        var delivered = new CountDownLatch(3);
        try {
            handler.broadcast(tick);
            handler.afterConnectionEstablished(session("a", messages, delivered));
            handler.feedUnavailable();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals("tick", mapper.readTree(messages.get(1)).get("type").asText());
            assertFalse(mapper.readTree(messages.getLast()).get("available").asBoolean());
        } finally { handler.shutdown(); }
    }

    @Test
    void slowSessionCannotBlockProducerAndIsClosedWhenQueueFills() throws Exception {
        var handler = new Mt5TickWebSocketHandler(mapper, new Ta4jMt5Sma9Adapter());
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        WebSocketSession slow = mock(WebSocketSession.class);
        when(slow.getId()).thenReturn("slow");
        when(slow.isOpen()).thenReturn(true);
        doAnswer(call -> { sending.countDown(); release.await(); return null; }).when(slow).sendMessage(any());
        doAnswer(call -> { release.countDown(); closed.countDown(); return null; }).when(slow).close(any());
        try {
            handler.afterConnectionEstablished(slow);
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (int i = 0; i < 100; i++) handler.broadcast(tick);
            });
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); handler.shutdown(); }
    }

    @Test
    void brokenSessionIsClosed() throws Exception {
        var handler = new Mt5TickWebSocketHandler(mapper, new Ta4jMt5Sma9Adapter());
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("broken");
        when(broken.isOpen()).thenReturn(true);
        doThrow(new IOException("test send failure")).when(broken).sendMessage(any());
        var closed = new CountDownLatch(1);
        doAnswer(call -> { closed.countDown(); return null; }).when(broken).close(any());
        try {
            handler.afterConnectionEstablished(broken);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
        } finally { handler.shutdown(); }
    }

    @Test
    void restSeedingRebroadcastsSameTickWithSmaWithoutWaitingForAnotherTrade() throws Exception {
        var adapter = new Ta4jMt5Sma9Adapter();
        var handler = new Mt5TickWebSocketHandler(mapper, adapter);
        long start = 1789991700L;
        long time = start + 8 * 300 + 1;
        var live = new Mt5Tick(time, time * 1000 + 123, 116, 118, 117, 4);
        var messages = new CopyOnWriteArrayList<String>();
        var delivered = new CountDownLatch(3);
        try {
            handler.afterConnectionEstablished(session("sma", messages, delivered));
            handler.broadcast(live);
            adapter.synchronize(java.util.stream.IntStream.range(0, 9).mapToObj(i ->
                    new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle(
                            start + i * 300, 100 + i, 120, 90, 100 + i, 1, 2)).toList());
            handler.refreshSma9();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(mapper.readTree(messages.get(1)).get("sma9").isNull());
            var enriched = mapper.readTree(messages.getLast());
            assertEquals(105, enriched.get("sma9").doubleValue());
            assertEquals(start + 8 * 300, enriched.get("sma9Time").longValue());
            assertEquals(live.timeMsc(), enriched.get("timeMsc").longValue());
            assertEquals(live.last(), enriched.get("last").doubleValue());
            assertTrue(enriched.get("sma21").isNull());
            assertTrue(enriched.get("sma21Time").isNull());
        } finally { handler.shutdown(); }
    }

    @Test
    void broadcastsBothAveragesFromSameTick() throws Exception {
        var adapter = new Ta4jMt5Sma9Adapter();
        long start = 1789991700L;
        adapter.synchronize(java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i ->
                new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle(
                        start + (i - 1) * 300, i, i + 30, i, i, 1, 2)).toList());
        var handler = new Mt5TickWebSocketHandler(mapper, adapter);
        var messages = new CopyOnWriteArrayList<String>();
        var delivered = new CountDownLatch(2);
        long time = start + 20 * 300;
        try {
            handler.afterConnectionEstablished(session("both", messages, delivered));
            handler.broadcast(new Mt5Tick(time + 1, (time + 1) * 1000 + 123, 41, 43, 42, 4));
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var json = mapper.readTree(messages.getLast());
            assertEquals(12, json.get("sma21").doubleValue());
            assertEquals(17 + 21.0 / 9, json.get("sma9").doubleValue(), 1e-9);
            assertEquals(time, json.get("sma21Time").longValue());
            assertEquals(time, json.get("sma9Time").longValue());
        } finally { handler.shutdown(); }
    }

    private WebSocketSession session(String id, List<String> messages, CountDownLatch latch) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        doAnswer(call -> {
            messages.add(((TextMessage) call.getArgument(0)).getPayload());
            latch.countDown();
            return null;
        }).when(session).sendMessage(any());
        return session;
    }
}
