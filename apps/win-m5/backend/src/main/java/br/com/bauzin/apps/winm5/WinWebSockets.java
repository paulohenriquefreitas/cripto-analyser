package br.com.bauzin.apps.winm5;
import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import br.com.bauzin.market.panic.panicscanner.api.ReplayWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WinWebSockets implements WebSocketConfigurer {
    private final Mt5TickWebSocketHandler ticks;
    private final ReplayWebSocketHandler replay;
    public WinWebSockets(Mt5TickWebSocketHandler ticks, ReplayWebSocketHandler replay) { this.ticks=ticks; this.replay=replay; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins={"http://localhost:5174", "http://127.0.0.1:5174", "http://localhost:4174", "http://127.0.0.1:4174"};
        registry.addHandler(ticks, "/ws/mt5/ticks").setAllowedOrigins(origins);
        registry.addHandler(replay, "/ws/replay/{replayId}").setAllowedOrigins(origins);
    }
}
