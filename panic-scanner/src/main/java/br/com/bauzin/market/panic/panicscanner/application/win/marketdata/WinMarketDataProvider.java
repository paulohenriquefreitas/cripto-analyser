package br.com.bauzin.market.panic.panicscanner.application.win.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.WinMarketDataClient;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;

import java.time.LocalDate;
import java.util.List;

public interface WinMarketDataProvider extends WinMarketDataClient {

    void start();

    void stop();

    void subscribe(String symbol);

    List<WinCandle> loadHistory(String symbol, LocalDate date);

    WinMarketDataStatus status();
}
