package br.com.bauzin.market.panic.config;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class Mt5WebSocketConfig implements WebSocketConfigurer {
    private final Mt5TickWebSocketHandler handler;

    public Mt5WebSocketConfig(Mt5TickWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/mt5/ticks").setAllowedOrigins(
                "http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:4173", "http://127.0.0.1:4173");
    }
}
