package br.com.bauzin.apps.threecandles;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5TickStreamLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit live activation in this application; historical reads do not need a stream. */
@Configuration
@ConditionalOnProperty(name="market.capture.enabled", havingValue="true", matchIfMissing=false)
@Import(Mt5TickStreamLifecycle.class)
public class LocalCaptureConfiguration {}
