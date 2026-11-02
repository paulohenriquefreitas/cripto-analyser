package br.com.bauzin.apps.threecandles;

import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@Import({ThreeCandlesController.class, ThreeCandleHistory.class, ThreeCandleHistoryLoader.class,
    ReplayHistoryProvider.class, LocalMt5DataConfiguration.class})
public class ThreeCandlesApplication {
    public static void main(String[] args) { SpringApplication.run(ThreeCandlesApplication.class, args); }
    @Bean ThreeCandleLive live(GetMt5CandlesUseCase candles, Mt5TickWebSocketHandler ticks, Mt5Symbol symbol, Mt5ProcessClient client) {
        return new ThreeCandleLive(candles,ticks,symbol,client,Clock.system(ZoneId.of("America/Sao_Paulo")));
    }
}
