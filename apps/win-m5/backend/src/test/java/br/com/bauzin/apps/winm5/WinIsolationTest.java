package br.com.bauzin.apps.winm5;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamLifecycle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeMonitoringLifecycle;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"market.capture.enabled=false", "win.market-data.provider=mock"})
class WinIsolationTest {
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;
    @Test void onlyWinEndpointsAndCaptureRequiresExplicitOptIn() {
        var routes=mappings.getHandlerMethods().keySet().stream().flatMap(m -> m.getPatternValues().stream()).toList();
        assertThat(routes).contains("/api/win/analysis", "/api/mt5/candles");
        assertThat(routes).noneMatch(p -> p.startsWith("/api/panic-scanner") || p.startsWith("/api/three-candles"));
        assertThat(context.getBeansOfType(Mt5TickStreamLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(Mt5TradeMonitoringLifecycle.class)).isEmpty();
    }
}
