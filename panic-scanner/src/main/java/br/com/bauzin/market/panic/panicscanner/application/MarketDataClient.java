package br.com.bauzin.market.panic.panicscanner.application;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;

import java.time.LocalDate;
import java.util.List;

/** Application port for market-data providers. */
public interface MarketDataClient {

    List<StockSummary> listStocks();

    List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to);
}
