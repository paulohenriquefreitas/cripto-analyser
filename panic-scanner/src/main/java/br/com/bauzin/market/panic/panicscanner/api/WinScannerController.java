package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.application.win.PanicScannerWinProperties;
import br.com.bauzin.market.panic.panicscanner.application.win.ScanWinUseCase;
import br.com.bauzin.market.panic.panicscanner.application.win.marketdata.WinMarketDataProvider;
import br.com.bauzin.market.panic.panicscanner.application.win.marketdata.WinMarketDataStatus;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinAnalysis;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.InMemoryWinMarketDataClient;

import jakarta.validation.Valid;

import java.time.OffsetDateTime;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/win")
public class WinScannerController {

    private final ScanWinUseCase scanWinUseCase;
    private final PanicScannerWinProperties properties;
    private final WinMarketDataProvider winMarketDataProvider;
    private final InMemoryWinMarketDataClient marketDataClient;

    public WinScannerController(ScanWinUseCase scanWinUseCase,
                                PanicScannerWinProperties properties,
                                WinMarketDataProvider winMarketDataProvider,
                                InMemoryWinMarketDataClient marketDataClient) {
        this.scanWinUseCase = scanWinUseCase;
        this.properties = properties;
        this.winMarketDataProvider = winMarketDataProvider;
        this.marketDataClient = marketDataClient;
    }

    @GetMapping("/analysis")
    public WinAnalysis analysis(@RequestParam(required = false) String contract,
                                @RequestParam(required = false) String contextTimeframe,
                                @RequestParam(required = false) String executionTimeframe) {
        return scanWinUseCase.execute(
                contract,
                contextTimeframe == null ? properties.contextTimeframe() : contextTimeframe,
                executionTimeframe == null ? properties.executionTimeframe() : executionTimeframe);
    }

    @GetMapping("/market-data/status")
    public WinMarketDataStatus marketDataStatus() {
        return winMarketDataProvider.status();
    }

    @PostMapping("/candles")
    public WinIngestionResponse ingestCandles(@Valid @RequestBody WinCandleIngestionRequest request) {
        if (request.append()) {
            marketDataClient.appendCandles(request.contract(), request.timeframe(), request.toCandles());
        } else {
            marketDataClient.replaceCandles(request.contract(), request.timeframe(), request.toCandles());
        }
        return new WinIngestionResponse(
                request.contract().trim().toUpperCase(),
                request.timeframe(),
                request.candles().size(),
                OffsetDateTime.now());
    }

    @PostMapping("/flow")
    public WinIngestionResponse ingestFlow(@Valid @RequestBody WinFlowIngestionRequest request) {
        marketDataClient.updateFlow(request.contract(), request.toFlow());
        return new WinIngestionResponse(
                request.contract().trim().toUpperCase(),
                null,
                1,
                OffsetDateTime.now());
    }
}
