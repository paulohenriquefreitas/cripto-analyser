package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Optional headless chart client. Uses only OLD WebSocket and its existing REST endpoint.
 * Never consumes canonical events. Refresh policy: startup, then OLD tick bucket rollover,
 * retry after five seconds when the official current bar is not available yet.
 */
public final class CanonicalShadowChartDriver implements AutoCloseable, WebSocket.Listener {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final URI origin;
    private final StringBuilder text = new StringBuilder();
    private volatile long latestBucket = -1, currentBucket = -1;
    private volatile boolean closed;
    private WebSocket socket;
    private long nextAttempt;
    private long refreshes;
    public CanonicalShadowChartDriver(URI origin) { this.origin = origin; }
    public void start() throws Exception {
        var ws = URI.create("ws://" + origin.getRawAuthority() + "/ws/mt5/ticks");
        socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(ws, this).get(6, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::refreshIfNeeded, 0, 100, TimeUnit.MILLISECONDS);
    }
    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
        text.append(part);
        if (last) {
            try {
                var message=JSON.readTree(text.toString());
                if (message.path("type").asText().equals("tick") && message.path("last").asDouble()>0)
                    latestBucket=Math.floorDiv(message.required("timeMsc").asLong(),300_000)*300_000;
            } catch(Exception ex) { System.err.println("[OLD-CHART-DRIVER] invalid message: " + ex); }
            text.setLength(0);
        }
        socket.request(1);return null;
    }
    @Override public void onError(WebSocket socket, Throwable ex) { System.err.println("[OLD-CHART-DRIVER] socket failed: " + ex); }
    private void refreshIfNeeded() {
        if(closed || (currentBucket>=0 && latestBucket<=currentBucket) || System.nanoTime()<nextAttempt)return;
        try {
            var response=http.send(HttpRequest.newBuilder(origin.resolve("/api/mt5/candles"))
                    .timeout(Duration.ofSeconds(65)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("candles HTTP "+response.statusCode());
            var bars=JSON.readTree(response.body());
            if(!bars.isArray() || bars.isEmpty())throw new IllegalStateException("empty OLD history");
            currentBucket=bars.get(bars.size()-1).required("time").asLong()*1000;
            refreshes++;
            System.out.println("[OLD-CHART-DRIVER] refresh="+refreshes+" bucket="+currentBucket);
        } catch(Exception ex) { if(!closed)System.err.println("[OLD-CHART-DRIVER] refresh failed: "+ex); }
        nextAttempt=System.nanoTime()+5_000_000_000L;
    }
    @Override public void close() {
        closed=true;executor.shutdownNow();if(socket!=null)socket.abort();
    }
    public static void main(String[] args) throws Exception {
        // Standalone bounded client for controlled runs without an open chart.
        int minutes=Integer.parseInt(args.length>1?args[1]:"65");
        if(minutes<1 || minutes>70)throw new IllegalArgumentException("minutes must be 1..70");
        try(var driver=new CanonicalShadowChartDriver(URI.create(args.length>0?args[0]:"http://127.0.0.1:8080"))) {
            driver.start();new CountDownLatch(1).await(minutes,TimeUnit.MINUTES);
        }
    }
}
