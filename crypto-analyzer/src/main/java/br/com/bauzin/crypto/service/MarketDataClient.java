package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.model.Candle;

import java.util.List;

public interface MarketDataClient {

    List<Candle> getCandles(
            String symbol,
            String interval,
            int limit);
}