package br.com.bauzin.market.panic.config;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Mt5Config {
    @Bean
    public Mt5ProcessClient mt5ProcessClient(Mt5Symbol symbol) {
        return new Mt5ProcessClient(symbol);
    }
}
