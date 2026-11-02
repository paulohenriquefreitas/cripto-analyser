package br.com.bauzin.apps.scanner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamLifecycle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeMonitoringLifecycle;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScannerIsolationTest {
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test void onlyScannerEndpointsAndNoMarketCapture() {
        var routes = mappings.getHandlerMethods().keySet().stream()
                .flatMap(m -> m.getPatternValues().stream()).toList();
        assertThat(routes).contains("/api/panic-scanner/market-scan");
        assertThat(routes).noneMatch(p -> p.startsWith("/api/win") || p.startsWith("/api/mt5") || p.startsWith("/api/three-candles"));
        assertThat(context.getBeansOfType(Mt5TickStreamLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(Mt5TradeMonitoringLifecycle.class)).isEmpty();
    }
}
