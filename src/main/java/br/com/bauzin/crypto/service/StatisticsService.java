package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.CryptoPriceSyncResult;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class StatisticsService {

    private final CryptoPriceHistoryService cryptoPriceHistoryService;

    public StatisticsService(CryptoPriceHistoryService cryptoPriceHistoryService) {
        this.cryptoPriceHistoryService = cryptoPriceHistoryService;
    }

//    public SequenceResult analyze() {
//        return null;
//    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Integer requestedCandles) {
        return cryptoPriceHistoryService.getCandles(symbol, interval, requestedCandles);
    }

    public CryptoPriceSyncResult syncPrices(String symbol) {
        return cryptoPriceHistoryService.sync(symbol);
    }

    public CryptoPriceSyncResult syncPrices(String symbol, String interval) {
        return cryptoPriceHistoryService.sync(symbol, interval);
    }

    public CryptoPriceSyncResult syncPrices(String symbol, String interval, Integer requestedCandles) {
        return cryptoPriceHistoryService.sync(symbol, interval, requestedCandles);
    }

    public CryptoPriceSyncResult syncPrices(String symbol,
                                            String interval,
                                            Integer requestedCandles,
                                            String loxCookie) {
        return cryptoPriceHistoryService.sync(symbol, interval, requestedCandles, loxCookie);
    }
}
