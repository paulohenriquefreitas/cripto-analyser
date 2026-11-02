package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Explicit shared wiring, without stream lifecycles or another backend dependency. */
@Import({Mt5Symbol.class, Ta4jMt5Sma9Adapter.class, GetMt5CandlesUseCase.class,
    Mt5TickWebSocketHandler.class, MarketStructureLiveObserver.class, Mt5MonitoringFlowService.class})
public class LocalMt5DataConfiguration {
    @Bean public Mt5ProcessClient mt5ProcessClient(Mt5Symbol symbol) {
        return new Mt5ProcessClient(symbol);
    }
}
