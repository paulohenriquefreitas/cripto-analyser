package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.client.LoxBrokerClient;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.CryptoPrice;
import br.com.bauzin.crypto.model.CryptoPriceSyncResult;
import br.com.bauzin.crypto.model.SupportedAsset;
import br.com.bauzin.crypto.repository.CryptoPriceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CryptoPriceSyncService {

    private static final int DEFAULT_REQUESTED_CANDLES = 1000;
    private static final String SOURCE = "LOXBROKER";

    private final CryptoPriceRepository cryptoPriceRepository;
    private final LoxBrokerClient loxBrokerClient;

    public CryptoPriceSyncService(CryptoPriceRepository cryptoPriceRepository,
                                  LoxBrokerClient loxBrokerClient) {
        this.cryptoPriceRepository = cryptoPriceRepository;
        this.loxBrokerClient = loxBrokerClient;
    }

    public CryptoPriceSyncResult sync(String symbol) {
        return sync(symbol, "1m", DEFAULT_REQUESTED_CANDLES, null);
    }

    public CryptoPriceSyncResult sync(String symbol, String interval) {
        return sync(symbol, interval, DEFAULT_REQUESTED_CANDLES, null);
    }

    public CryptoPriceSyncResult sync(String symbol, String interval, Integer requestedCandles) {
        return sync(symbol, interval, requestedCandles, null);
    }

    public CryptoPriceSyncResult sync(String symbol,
                                      String interval,
                                      Integer requestedCandles,
                                      String loxCookie) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(interval);
        int safeRequestedCandles = normalizeRequestedCandles(requestedCandles);
        Instant latestClosedOpenTime = CandleIntervalHelper.resolveLatestClosedOpenTime(normalizedInterval);
        Instant targetStartTime = CandleIntervalHelper.resolveInitialOpenTime(normalizedInterval, safeRequestedCandles);

        List<CryptoPrice> cachedWindow = cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetweenOrderByTimestampAsc(
                normalizedSymbol,
                normalizedInterval,
                targetStartTime,
                latestClosedOpenTime);

        List<CryptoPrice> fetchedPrices = new ArrayList<>();

        if (cachedWindow.isEmpty()) {
            fetchedPrices.addAll(fetchRange(
                    normalizedSymbol,
                    normalizedInterval,
                    targetStartTime,
                    latestClosedOpenTime,
                    safeRequestedCandles,
                    loxCookie));
        } else {
            Instant oldestStoredTimestamp = cachedWindow.getFirst().getTimestamp();
            Instant newestStoredTimestamp = cachedWindow.getLast().getTimestamp();

            if (oldestStoredTimestamp.isAfter(targetStartTime)) {
                Instant missingOlderEndTime = CandleIntervalHelper.resolvePreviousOpenTime(oldestStoredTimestamp, normalizedInterval);
                int missingOlderCandles = countCandlesBetween(targetStartTime, missingOlderEndTime, normalizedInterval);
                fetchedPrices.addAll(fetchRange(
                        normalizedSymbol,
                        normalizedInterval,
                        targetStartTime,
                        missingOlderEndTime,
                        missingOlderCandles,
                        loxCookie));
            }

            if (newestStoredTimestamp.isBefore(latestClosedOpenTime)) {
                Instant missingNewerStartTime = CandleIntervalHelper.resolveNextOpenTime(newestStoredTimestamp, normalizedInterval);
                int missingNewerCandles = countCandlesBetween(missingNewerStartTime, latestClosedOpenTime, normalizedInterval);
                fetchedPrices.addAll(fetchRange(
                        normalizedSymbol,
                        normalizedInterval,
                        missingNewerStartTime,
                        latestClosedOpenTime,
                        missingNewerCandles,
                        loxCookie));
            }

            int expectedCandles = countCandlesBetween(targetStartTime, latestClosedOpenTime, normalizedInterval);
            if (cachedWindow.size() + fetchedPrices.size() < expectedCandles) {
                fetchedPrices.addAll(fetchRange(
                        normalizedSymbol,
                        normalizedInterval,
                        targetStartTime,
                        latestClosedOpenTime,
                        safeRequestedCandles,
                        loxCookie));
            }
        }

        int persistedRecords = persistNewPrices(normalizedSymbol, normalizedInterval, fetchedPrices);
        Instant latestStoredTimestamp = cryptoPriceRepository
                .findFirstBySymbolAndIntervalOrderByTimestampDesc(normalizedSymbol, normalizedInterval)
                .map(CryptoPrice::getTimestamp)
                .orElse(null);

        return CryptoPriceSyncResult.builder()
                .symbol(normalizedSymbol)
                .interval(normalizedInterval)
                .source(fetchedPrices.isEmpty() ? SOURCE : fetchedPrices.getFirst().getSource())
                .startTime(targetStartTime)
                .latestStoredTimestamp(latestStoredTimestamp)
                .fetchedRecords(fetchedPrices.size())
                .persistedRecords(persistedRecords)
                .build();
    }

    private List<CryptoPrice> fetchRange(String symbol,
                                         String interval,
                                         Instant startTime,
                                         Instant endTime,
                                         int requestedCandles,
                                         String loxCookie) {
        if (requestedCandles <= 0 || startTime.isAfter(endTime)) {
            return List.of();
        }

        return loxBrokerClient.getCandles(
                        symbol,
                        interval,
                        startTime,
                        endTime,
                        requestedCandles,
                        loxCookie)
                .stream()
                .map(candle -> toCryptoPrice(symbol, interval, candle))
                .toList();
    }

    private int persistNewPrices(String symbol, String interval, List<CryptoPrice> prices) {
        if (prices.isEmpty()) {
            return 0;
        }

        List<CryptoPrice> uniquePrices = deduplicateByTimestamp(prices);
        Instant startTime = uniquePrices.stream()
                .map(CryptoPrice::getTimestamp)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant endTime = uniquePrices.stream()
                .map(CryptoPrice::getTimestamp)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        Set<Instant> existingTimestamps = cryptoPriceRepository
                .findBySymbolAndIntervalAndTimestampBetween(symbol, interval, startTime, endTime)
                .stream()
                .map(CryptoPrice::getTimestamp)
                .collect(Collectors.toSet());

        List<CryptoPrice> newPrices = uniquePrices.stream()
                .filter(price -> !existingTimestamps.contains(price.getTimestamp()))
                .toList();

        if (newPrices.isEmpty()) {
            return 0;
        }

        int persistedRecords = 0;

        for (CryptoPrice price : newPrices) {
            try {
                cryptoPriceRepository.save(price);
                persistedRecords++;
            } catch (DuplicateKeyException ex) {
                log.debug("Duplicate price ignored during sync for symbol {} interval {} timestamp {}",
                        symbol,
                        interval,
                        price.getTimestamp());
            }
        }

        return persistedRecords;
    }

    private List<CryptoPrice> deduplicateByTimestamp(List<CryptoPrice> prices) {
        Map<Instant, CryptoPrice> pricesByTimestamp = new LinkedHashMap<>();

        for (CryptoPrice price : prices) {
            pricesByTimestamp.put(price.getTimestamp(), price);
        }

        return new ArrayList<>(pricesByTimestamp.values());
    }

    private String normalizeSymbol(String symbol) {
        return SupportedAsset.normalize(symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol);
    }

    private String normalizeInterval(String interval) {
        return interval == null ? "1m" : interval.trim().toLowerCase(Locale.ROOT);
    }

    private int normalizeRequestedCandles(Integer requestedCandles) {
        if (requestedCandles == null || requestedCandles < 1) {
            return DEFAULT_REQUESTED_CANDLES;
        }

        return requestedCandles;
    }

    private int countCandlesBetween(Instant startTime, Instant endTime, String interval) {
        if (startTime.isAfter(endTime)) {
            return 0;
        }

        long intervalMillis = CandleIntervalHelper.toMillis(interval);
        long difference = endTime.toEpochMilli() - startTime.toEpochMilli();

        return (int) (difference / intervalMillis) + 1;
    }

    private CryptoPrice toCryptoPrice(String symbol, String interval, Candle candle) {
        return CryptoPrice.builder()
                .symbol(symbol)
                .interval(interval)
                .timestamp(candle.getOpenTime())
                .price(candle.getClose())
                .closeTime(candle.getCloseTime())
                .open(candle.getOpen())
                .high(candle.getHigh())
                .low(candle.getLow())
                .close(candle.getClose())
                .volume(candle.getVolume())
                .quoteAssetVolume(candle.getQuoteAssetVolume())
                .trades(candle.getTrades())
                .takerBuyBaseVolume(candle.getTakerBuyBaseVolume())
                .takerBuyQuoteVolume(candle.getTakerBuyQuoteVolume())
                .source(candle.getSource() == null ? SOURCE : candle.getSource())
                .build();
    }
}
