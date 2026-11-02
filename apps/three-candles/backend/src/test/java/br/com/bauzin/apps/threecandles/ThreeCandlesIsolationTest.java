package br.com.bauzin.apps.threecandles;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="market.capture.enabled=false")
class ThreeCandlesIsolationTest {
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;
    @Test void ownEndpointsAndLocalClientWithoutAnotherBackend() {
        var routes=mappings.getHandlerMethods().keySet().stream().flatMap(m -> m.getPatternValues().stream()).toList();
        assertThat(routes).contains("/api/three-candles/live", "/api/three-candles/replays");
        assertThat(routes).noneMatch(p -> p.startsWith("/api/panic-scanner") || p.startsWith("/api/win") || p.startsWith("/api/mt5"));
        assertThat(context.getBeansOfType(Mt5TickStreamLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(Mt5TradeMonitoringLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(BrapiMarketDataClient.class)).isEmpty();
        assertThat(context.getBeansOfType(Mt5ProcessClient.class)).hasSize(1);
        assertThat(context.getBean(Mt5ProcessClient.class).getClass()).isEqualTo(Mt5ProcessClient.class);
    }
}
