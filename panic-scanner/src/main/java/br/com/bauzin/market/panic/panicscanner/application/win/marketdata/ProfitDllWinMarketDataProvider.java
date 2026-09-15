package br.com.bauzin.market.panic.panicscanner.application.win.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.candle.WinCandleBuilder;
import br.com.bauzin.market.panic.panicscanner.application.win.profitdll.ProfitDllBridgeTrade;
import br.com.bauzin.market.panic.panicscanner.application.win.profitdll.ProfitDllConfig;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "win.market-data", name = "provider", havingValue = "profitdll")
public class ProfitDllWinMarketDataProvider implements WinMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ProfitDllWinMarketDataProvider.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");

    private final ProfitDllConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectExecutor;
    private final WinCandleBuilder builder1m = new WinCandleBuilder(Duration.ofMinutes(1));
    private final WinCandleBuilder builder5m = new WinCandleBuilder(Duration.ofMinutes(5));
    private final Map<String, List<WinCandle>> candlesByTimeframe = new ConcurrentHashMap<>();
    private final AtomicLong tradesReceived = new AtomicLong();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean mockWarmUpCompleted = new AtomicBoolean();
    private final AtomicBoolean technicalAnalysisReadyLogged = new AtomicBoolean();
    private volatile OffsetDateTime lastTradeTime;
    private volatile BigDecimal lastPrice;
    private volatile OffsetDateTime lastClosedCandle;
    private volatile String symbol;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean subscribed;
    private volatile String lastMessage = "ProfitDLL bridge não iniciado";
    private volatile WebSocket webSocket;
    private volatile Duration nextReconnectBackoff;

    public ProfitDllWinMarketDataProvider(ProfitDllConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectionTimeout())
                .build();
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "profitdll-bridge-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        this.symbol = config.ticker();
        this.nextReconnectBackoff = config.reconnectInitialBackoff();
    }

    @PostConstruct
    @Override
    public void start() {
        if (running) return;
        running = true;
        subscribe(config.ticker());
        connect();
    }

    @PreDestroy
    @Override
    public void stop() {
        running = false;
        connected = false;
        subscribed = false;
        connecting.set(false);
        reconnectScheduled.set(false);
        WebSocket current = webSocket;
        if (current != null) {
            current.abort();
        }
        reconnectExecutor.shutdownNow();
        lastMessage = "ProfitDLL bridge parado";
    }

    @Override
    public void subscribe(String symbol) {
        this.symbol = symbol;
        subscribed = connected;
    }

    @Override
    public List<WinCandle> loadHistory(String symbol, LocalDate date) {
        return getIntradayCandles(symbol, "5m");
    }

    @Override
    public List<WinCandle> getIntradayCandles(String contract, String timeframe) {
        WinCandle current = "1m".equalsIgnoreCase(timeframe) ? builder1m.currentCandle() : builder5m.currentCandle();
        List<WinCandle> closed = candlesByTimeframe.getOrDefault(normalize(timeframe), List.of());
        if (current == null) {
            return closed;
        }
        return java.util.stream.Stream.concat(closed.stream(), java.util.stream.Stream.of(current))
                .sorted(Comparator.comparing(WinCandle::timestamp))
                .toList();
    }

    @Override
    public WinFlowSnapshot getFlowSnapshot(String contract) {
        return WinFlowSnapshot.unavailable();
    }

    @Override
    public WinMarketDataStatus status() {
        return new WinMarketDataStatus(
                "profitdll",
                false,
                connected,
                subscribed,
                symbol,
                lastTradeTime,
                lastPrice,
                tradesReceived.get(),
                lastClosedCandle,
                lastMessage);
    }

    void handleBridgeMessage(String message) {
        try {
            ProfitDllBridgeTrade bridgeTrade = objectMapper.readValue(message, ProfitDllBridgeTrade.class);
            if (!bridgeTrade.isTrade() || !bridgeTrade.matches(config.ticker(), config.exchange())) {
                return;
            }
            WinTrade trade = bridgeTrade.toWinTrade();
            warmUpIfNecessary(trade);
            log.info("WIN trade received: price={} quantity={}", trade.price(), trade.quantity());
            onTrade(trade);
            logTechnicalAnalysisReadyIfApplicable();
        } catch (Exception exception) {
            lastMessage = "Mensagem inválida do ProfitDLL bridge: " + exception.getMessage();
            log.warn("ProfitDLL bridge invalid message message={}", exception.getMessage());
        }
    }

    private void connect() {
        if (!running || !connecting.compareAndSet(false, true)) return;
        log.info("ProfitDLL bridge connecting...");
        lastMessage = "Conectando ao ProfitDLL bridge " + config.bridgeUrl();
        httpClient.newWebSocketBuilder()
                .connectTimeout(config.connectionTimeout())
                .buildAsync(config.bridgeUrl(), new BridgeListener())
                .whenComplete((socket, exception) -> {
                    connecting.set(false);
                    if (exception != null) {
                        connected = false;
                        subscribed = false;
                        lastMessage = "Falha ao conectar ao ProfitDLL bridge: " + exception.getMessage();
                        log.warn("ProfitDLL bridge connection failed message={}", exception.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    private void onConnected(WebSocket socket) {
        webSocket = socket;
        connected = true;
        subscribed = symbol != null && !symbol.isBlank();
        nextReconnectBackoff = config.reconnectInitialBackoff();
        lastMessage = "ProfitDLL bridge conectado em " + config.bridgeUrl();
        log.info("ProfitDLL bridge connected");
    }

    private void onDisconnected(String message) {
        connected = false;
        subscribed = false;
        webSocket = null;
        if (message != null && !message.isBlank()) {
            lastMessage = message;
        }
        if (running) {
            log.info("ProfitDLL bridge disconnected");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || !reconnectScheduled.compareAndSet(false, true)) return;
        Duration delay = nextReconnectBackoff;
        log.info("ProfitDLL bridge reconnecting...");
        lastMessage = "ProfitDLL bridge reconectando em " + delay.toSeconds() + "s";
        reconnectExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            nextReconnectBackoff = nextBackoff(delay);
            connect();
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(config.reconnectMaxBackoff()) > 0 ? config.reconnectMaxBackoff() : doubled;
    }

    private void onTrade(WinTrade trade) {
        tradesReceived.incrementAndGet();
        lastTradeTime = trade.timestamp().atZone(MARKET_ZONE).toOffsetDateTime();
        lastPrice = BigDecimal.valueOf(trade.price());
        appendClosed("1m", builder1m.onTrade(trade));
        appendClosed("5m", builder5m.onTrade(trade));
    }

    private void appendClosed(String timeframe, List<WinCandle> candles) {
        if (candles.isEmpty()) return;
        candlesByTimeframe.merge(normalize(timeframe), candles, (existing, incoming) ->
                java.util.stream.Stream.concat(existing.stream(), incoming.stream())
                        .collect(java.util.stream.Collectors.toMap(
                                candle -> candle.timestamp().toInstant(),
                                candle -> candle,
                                (previous, current) -> current))
                        .values()
                        .stream()
                        .sorted(Comparator.comparing(WinCandle::timestamp))
                        .toList());
        lastClosedCandle = candles.get(candles.size() - 1).endTime();
        log.info("win candle closed timeframe={} end={} close={} trades={}",
                timeframe,
                lastClosedCandle,
                candles.get(candles.size() - 1).close(),
                candles.get(candles.size() - 1).tradeCount());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void warmUpIfNecessary(WinTrade firstRealtimeTrade) {
        if (!config.mockWarmUpEnabled() || !mockWarmUpCompleted.compareAndSet(false, true)) {
            return;
        }
        log.info("WIN mock warm-up started");
        int candles5m = config.mockWarmUpCandles();
        LocalDateTime firstRealtimeTimestamp = safeWarmUpAnchor(firstRealtimeTrade.timestamp());
        List<WinCandle> warmed5m = warmUpCandles(
                firstRealtimeTrade,
                Duration.ofMinutes(5),
                candles5m,
                windowStart(firstRealtimeTimestamp, Duration.ofMinutes(5)));
        List<WinCandle> warmed1m = warmUpCandles(
                firstRealtimeTrade,
                Duration.ofMinutes(1),
                candles5m * 5,
                windowStart(firstRealtimeTimestamp, Duration.ofMinutes(1)));
        appendClosed("5m", warmed5m);
        appendClosed("1m", warmed1m);
        lastMessage = "WIN mock warm-up concluído com " + candles5m + " candles 5m";
        log.info("WIN mock warm-up completed: {} candles loaded", candles5m);
    }

    private List<WinCandle> warmUpCandles(WinTrade referenceTrade,
                                          Duration timeframe,
                                          int count,
                                          LocalDateTime firstRealtimeWindowStart) {
        List<WinCandle> candles = new ArrayList<>(count);
        BigDecimal referencePrice = BigDecimal.valueOf(referenceTrade.price()).setScale(4, RoundingMode.HALF_UP);
        for (int i = count; i > 0; i--) {
            LocalDateTime start = firstRealtimeWindowStart.minus(timeframe.multipliedBy(i));
            LocalDateTime end = start.plus(timeframe);
            BigDecimal variation = BigDecimal.valueOf((i % 9) - 4L).multiply(BigDecimal.valueOf(5));
            BigDecimal open = referencePrice.subtract(BigDecimal.valueOf(i * 2L)).add(variation).setScale(4, RoundingMode.HALF_UP);
            BigDecimal close = open.add(BigDecimal.valueOf(i % 2 == 0 ? 7 : -6)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).add(BigDecimal.valueOf(12)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).subtract(BigDecimal.valueOf(12)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal volume = BigDecimal.valueOf(100 + (count - i) * 3L).setScale(4, RoundingMode.HALF_UP);
            candles.add(new WinCandle(
                    offset(end),
                    open,
                    high,
                    low,
                    close,
                    volume,
                    close.multiply(volume).setScale(4, RoundingMode.HALF_UP),
                    Math.max(1, timeframe.toMinutes()),
                    offset(start),
                    offset(end)));
        }
        return candles;
    }

    private LocalDateTime windowStart(LocalDateTime timestamp, Duration timeframe) {
        long minute = timestamp.getMinute();
        long bucket = minute - minute % timeframe.toMinutes();
        return timestamp.withMinute((int) bucket).withSecond(0).withNano(0);
    }

    private LocalDateTime safeWarmUpAnchor(LocalDateTime firstRealtimeTimestamp) {
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        return firstRealtimeTimestamp.isAfter(now) ? now : firstRealtimeTimestamp;
    }

    private OffsetDateTime offset(LocalDateTime dateTime) {
        return dateTime.atZone(MARKET_ZONE).toOffsetDateTime();
    }

    private void logTechnicalAnalysisReadyIfApplicable() {
        if (technicalAnalysisReadyLogged.get()) {
            return;
        }
        int required5m = config.mockWarmUpCandles();
        int required1m = required5m * 5;
        if (getIntradayCandles(symbol, "5m").size() >= required5m
                && getIntradayCandles(symbol, "1m").size() >= required1m
                && technicalAnalysisReadyLogged.compareAndSet(false, true)) {
            log.info("WIN technical analysis ready");
        }
    }

    private final class BridgeListener implements WebSocket.Listener {

        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            onConnected(webSocket);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (text) {
                text.append(data);
                if (last) {
                    handleBridgeMessage(text.toString());
                    text.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onDisconnected("ProfitDLL bridge desconectado: " + statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onDisconnected("Erro no ProfitDLL bridge: " + error.getMessage());
        }
    }
}
