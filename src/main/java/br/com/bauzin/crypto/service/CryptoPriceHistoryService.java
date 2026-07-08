package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.CryptoPrice;
import br.com.bauzin.crypto.model.CryptoPriceSyncResult;
import br.com.bauzin.crypto.model.SupportedAsset;
import br.com.bauzin.crypto.repository.CryptoPriceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CryptoPriceHistoryService {

    private final CryptoPriceRepository cryptoPriceRepository;
    private final CryptoPriceSyncService cryptoPriceSyncService;

    public CryptoPriceHistoryService(CryptoPriceRepository cryptoPriceRepository,
                                     CryptoPriceSyncService cryptoPriceSyncService) {
        this.cryptoPriceRepository = cryptoPriceRepository;
        this.cryptoPriceSyncService = cryptoPriceSyncService;
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Integer requestedCandles) {
        return getCandles(symbol, interval, requestedCandles, null);
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Integer requestedCandles,
                                    String loxCookie) {
        int safeRequestedCandles = normalizeRequestedCandles(requestedCandles);
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(interval);

        cryptoPriceSyncService.sync(normalizedSymbol, normalizedInterval, safeRequestedCandles, loxCookie);

        return cryptoPriceRepository
                .findBySymbolAndIntervalOrderByTimestampDesc(
                        normalizedSymbol,
                        normalizedInterval,
                        PageRequest.of(0, safeRequestedCandles))
                .stream()
                .map(this::toCandle)
                .sorted(java.util.Comparator.comparing(Candle::getOpenTime))
                .toList();
    }

    public CryptoPriceSyncResult sync(String symbol, String interval, Integer requestedCandles) {
        return cryptoPriceSyncService.sync(symbol, interval, requestedCandles);
    }

    public CryptoPriceSyncResult sync(String symbol,
                                      String interval,
                                      Integer requestedCandles,
                                      String loxCookie) {
        return cryptoPriceSyncService.sync(symbol, interval, requestedCandles, loxCookie);
    }

    public CryptoPriceSyncResult sync(String symbol, String interval) {
        return sync(symbol, interval, null);
    }

    public CryptoPriceSyncResult sync(String symbol) {
        return cryptoPriceSyncService.sync(symbol);
    }

    private Candle toCandle(CryptoPrice price) {
        return Candle.builder()
                .openTime(price.getTimestamp())
                .closeTime(price.getCloseTime())
                .open(price.getOpen())
                .high(price.getHigh())
                .low(price.getLow())
                .close(price.getClose())
                .volume(price.getVolume())
                .quoteAssetVolume(price.getQuoteAssetVolume())
                .trades(price.getTrades())
                .takerBuyBaseVolume(price.getTakerBuyBaseVolume())
                .takerBuyQuoteVolume(price.getTakerBuyQuoteVolume())
                .source(price.getSource())
                .build();
    }

    private int normalizeRequestedCandles(Integer requestedCandles) {
        if (requestedCandles == null || requestedCandles < 1) {
            return 100;
        }

        return requestedCandles;
    }

    private String normalizeSymbol(String symbol) {
        return SupportedAsset.normalize(symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol);
    }

    private String normalizeInterval(String interval) {
        return interval == null ? "1m" : interval.trim().toLowerCase();
    }
}
