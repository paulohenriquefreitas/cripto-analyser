package br.com.bauzin.apps.winm5;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamLifecycle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TradeMonitoringLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Only this executable may own the stream; disabled unless explicitly requested. */
@Configuration
@ConditionalOnProperty(name="market.capture.enabled", havingValue="true", matchIfMissing=false)
@Import({Mt5TickStreamLifecycle.class, Mt5TradeMonitoringLifecycle.class})
public class MarketCaptureConfiguration {}
