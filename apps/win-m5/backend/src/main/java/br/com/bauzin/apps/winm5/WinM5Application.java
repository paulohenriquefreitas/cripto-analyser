package br.com.bauzin.apps.winm5;

import br.com.bauzin.market.panic.panicscanner.api.*;
import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.application.win.*;
import br.com.bauzin.market.panic.panicscanner.application.win.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.application.win.profitdll.ProfitDllConfig;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@SpringBootApplication
@EnableConfigurationProperties({PanicScannerWinProperties.class, ProfitDllConfig.class})
@Import({WinScannerController.class, PanicScannerExceptionHandler.class,
    Mt5CandlesController.class, Mt5LiveConfigController.class, VolumeProfileController.class,
    Mt5TickWebSocketHandler.class, ReplaySessionController.class, ReplaySessionRegistry.class,
    ReplayWebSocketHandler.class, GetMt5CandlesUseCase.class, ScanWinUseCase.class, AnalyzeWinUseCase.class,
    MockWinMarketDataProvider.class, ProfitDllWinMarketDataProvider.class, ReplayHistoryProvider.class,
    Ta4jWinTechnicalAnalysisAdapter.class, Ta4jMt5Sma9Adapter.class, Mt5Symbol.class,
    MarketStructureLiveObserver.class, Mt5MonitoringFlowService.class, Mt5VolumeProfileService.class,
    InMemoryWinMarketDataClient.class})
public class WinM5Application {
    public static void main(String[] args) { SpringApplication.run(WinM5Application.class, args); }
    @Bean Mt5ProcessClient mt5ProcessClient(Mt5Symbol symbol) { return new Mt5ProcessClient(symbol); }
}
