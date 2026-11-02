package br.com.bauzin.apps.scanner;

import br.com.bauzin.market.panic.panicscanner.api.*;
import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.entry.PanicScannerEntryProperties;
import br.com.bauzin.market.panic.panicscanner.application.usecase.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jTechnicalAnalysisAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties({PanicScannerProperties.class, PanicScannerEntryProperties.class, BrapiProperties.class})
@Import({PanicScannerController.class, PanicScannerExceptionHandler.class,
        AnalyzeStockUseCase.class, ScanStocksUseCase.class, ScanB3MarketUseCase.class,
        BrapiMarketDataClient.class, Ta4jTechnicalAnalysisAdapter.class})
public class ScannerApplication {
    public static void main(String[] args) { SpringApplication.run(ScannerApplication.class, args); }
}
