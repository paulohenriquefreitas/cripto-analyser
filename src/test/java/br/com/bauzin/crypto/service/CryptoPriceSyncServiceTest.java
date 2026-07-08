package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.client.LoxBrokerClient;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.CryptoPrice;
import br.com.bauzin.crypto.repository.CryptoPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DuplicateKeyException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoPriceSyncServiceTest {

    @Mock
    private CryptoPriceRepository cryptoPriceRepository;

    @Mock
    private LoxBrokerClient loxBrokerClient;

    @InjectMocks
    private CryptoPriceSyncService cryptoPriceSyncService;

    @Test
    void shouldAppendOnlyNewerCandlesWhenWindowAdvances() {
        String symbol = "BTCUSDT";
        String interval = "1m";
        int requestedCandles = 20;
        Instant latestClosedOpenTime = CandleIntervalHelper.resolveLatestClosedOpenTime(interval);
        Instant targetStartTime = CandleIntervalHelper.resolveInitialOpenTime(interval, requestedCandles);

        List<CryptoPrice> cachedWindow = pricesBetween(symbol, interval, targetStartTime, 10);
        Instant newestStoredTimestamp = cachedWindow.getLast().getTimestamp();
        List<CryptoPrice> missingNewerPrices = pricesBetween(
                symbol,
                interval,
                CandleIntervalHelper.resolveNextOpenTime(newestStoredTimestamp, interval),
                10);

        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetweenOrderByTimestampAsc(
                symbol,
                interval,
                targetStartTime,
                latestClosedOpenTime))
                .thenReturn(cachedWindow);
        when(loxBrokerClient.getCandles(
                eq(symbol),
                eq(interval),
                eq(CandleIntervalHelper.resolveNextOpenTime(newestStoredTimestamp, interval)),
                eq(latestClosedOpenTime),
                eq(10),
                eq((String) null)))
                .thenReturn(toCandles(missingNewerPrices));
        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetween(
                symbol,
                interval,
                missingNewerPrices.getFirst().getTimestamp(),
                missingNewerPrices.getLast().getTimestamp()))
                .thenReturn(List.of());
        for (CryptoPrice price : missingNewerPrices) {
            when(cryptoPriceRepository.save(price)).thenReturn(price);
        }
        when(cryptoPriceRepository.findFirstBySymbolAndIntervalOrderByTimestampDesc(symbol, interval))
                .thenReturn(Optional.of(missingNewerPrices.getLast()));

        var result = cryptoPriceSyncService.sync(symbol, interval, requestedCandles);

        assertThat(result.getFetchedRecords()).isEqualTo(10);
        assertThat(result.getPersistedRecords()).isEqualTo(10);

        ArgumentCaptor<CryptoPrice> savedPricesCaptor = ArgumentCaptor.forClass(CryptoPrice.class);
        verify(cryptoPriceRepository, times(10)).save(savedPricesCaptor.capture());
        assertThat(savedPricesCaptor.getAllValues())
                .extracting(CryptoPrice::getTimestamp)
                .containsExactlyElementsOf(missingNewerPrices.stream().map(CryptoPrice::getTimestamp).toList());
    }

    @Test
    void shouldPersistOnlyMissingCandlesWhenFallbackFetchContainsDuplicates() {
        String symbol = "BTCUSDT";
        String interval = "1m";
        int requestedCandles = 20;
        Instant latestClosedOpenTime = CandleIntervalHelper.resolveLatestClosedOpenTime(interval);
        Instant targetStartTime = CandleIntervalHelper.resolveInitialOpenTime(interval, requestedCandles);

        List<CryptoPrice> firstBlock = pricesBetween(symbol, interval, targetStartTime, 8);
        Instant lastBlockStart = CandleIntervalHelper.resolveNextOpenTime(targetStartTime, interval).plusMillis(14L * CandleIntervalHelper.toMillis(interval));
        List<CryptoPrice> lastBlock = pricesBetween(symbol, interval, lastBlockStart, 5);
        List<CryptoPrice> cachedWindow = List.copyOf(
                java.util.stream.Stream.concat(firstBlock.stream(), lastBlock.stream()).toList());
        List<CryptoPrice> fullWindow = pricesBetween(symbol, interval, targetStartTime, requestedCandles);
        List<CryptoPrice> missingPrices = fullWindow.subList(8, 15);

        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetweenOrderByTimestampAsc(
                symbol,
                interval,
                targetStartTime,
                latestClosedOpenTime))
                .thenReturn(cachedWindow);
        when(loxBrokerClient.getCandles(
                eq(symbol),
                eq(interval),
                eq(targetStartTime),
                eq(latestClosedOpenTime),
                eq(requestedCandles),
                eq((String) null)))
                .thenReturn(toCandles(fullWindow));
        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetween(
                symbol,
                interval,
                targetStartTime,
                latestClosedOpenTime))
                .thenReturn(cachedWindow);
        for (CryptoPrice price : missingPrices) {
            when(cryptoPriceRepository.save(price)).thenReturn(price);
        }
        when(cryptoPriceRepository.findFirstBySymbolAndIntervalOrderByTimestampDesc(symbol, interval))
                .thenReturn(Optional.of(fullWindow.getLast()));

        var result = cryptoPriceSyncService.sync(symbol, interval, requestedCandles);

        assertThat(result.getFetchedRecords()).isEqualTo(requestedCandles);
        assertThat(result.getPersistedRecords()).isEqualTo(missingPrices.size());

        ArgumentCaptor<CryptoPrice> savedPricesCaptor = ArgumentCaptor.forClass(CryptoPrice.class);
        verify(cryptoPriceRepository, times(missingPrices.size())).save(savedPricesCaptor.capture());
        assertThat(savedPricesCaptor.getAllValues())
                .extracting(CryptoPrice::getTimestamp)
                .containsExactlyElementsOf(missingPrices.stream().map(CryptoPrice::getTimestamp).toList());
    }

    @Test
    void shouldIgnoreDuplicateKeyDuringIndividualPersistenceAndContinue() {
        String symbol = "BTCUSDT";
        String interval = "1m";
        int requestedCandles = 20;
        Instant latestClosedOpenTime = CandleIntervalHelper.resolveLatestClosedOpenTime(interval);
        Instant targetStartTime = CandleIntervalHelper.resolveInitialOpenTime(interval, requestedCandles);

        List<CryptoPrice> cachedWindow = pricesBetween(symbol, interval, targetStartTime, 10);
        Instant newestStoredTimestamp = cachedWindow.getLast().getTimestamp();
        List<CryptoPrice> missingNewerPrices = pricesBetween(
                symbol,
                interval,
                CandleIntervalHelper.resolveNextOpenTime(newestStoredTimestamp, interval),
                10);

        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetweenOrderByTimestampAsc(
                symbol,
                interval,
                targetStartTime,
                latestClosedOpenTime))
                .thenReturn(cachedWindow);
        when(loxBrokerClient.getCandles(
                eq(symbol),
                eq(interval),
                eq(CandleIntervalHelper.resolveNextOpenTime(newestStoredTimestamp, interval)),
                eq(latestClosedOpenTime),
                eq(10),
                eq((String) null)))
                .thenReturn(toCandles(missingNewerPrices));
        when(cryptoPriceRepository.findBySymbolAndIntervalAndTimestampBetween(
                symbol,
                interval,
                missingNewerPrices.getFirst().getTimestamp(),
                missingNewerPrices.getLast().getTimestamp()))
                .thenReturn(List.of());
        when(cryptoPriceRepository.save(missingNewerPrices.getFirst()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        for (CryptoPrice price : missingNewerPrices.subList(1, missingNewerPrices.size())) {
            when(cryptoPriceRepository.save(price)).thenReturn(price);
        }
        when(cryptoPriceRepository.findFirstBySymbolAndIntervalOrderByTimestampDesc(symbol, interval))
                .thenReturn(Optional.of(missingNewerPrices.getLast()));

        var result = cryptoPriceSyncService.sync(symbol, interval, requestedCandles);

        assertThat(result.getFetchedRecords()).isEqualTo(10);
        assertThat(result.getPersistedRecords()).isEqualTo(9);
        verify(cryptoPriceRepository, times(10)).save(any(CryptoPrice.class));
    }

    private List<CryptoPrice> pricesBetween(String symbol, String interval, Instant startTime, int count) {
        long step = CandleIntervalHelper.toMillis(interval);

        return IntStream.range(0, count)
                .mapToObj(index -> priceAt(symbol, interval, startTime.plusMillis(step * index)))
                .toList();
    }

    private CryptoPrice priceAt(String symbol, String interval, Instant timestamp) {
        return CryptoPrice.builder()
                .symbol(symbol)
                .interval(interval)
                .timestamp(timestamp)
                .closeTime(timestamp.plusMillis(CandleIntervalHelper.toMillis(interval) - 1))
                .price(BigDecimal.ONE)
                .open(BigDecimal.ONE)
                .high(BigDecimal.ONE)
                .low(BigDecimal.ONE)
                .close(BigDecimal.ONE)
                .volume(BigDecimal.ONE)
                .quoteAssetVolume(BigDecimal.ONE)
                .trades(1L)
                .takerBuyBaseVolume(BigDecimal.ONE)
                .takerBuyQuoteVolume(BigDecimal.ONE)
                .source("LOXBROKER")
                .build();
    }

    private List<Candle> toCandles(List<CryptoPrice> prices) {
        return prices.stream()
                .map(price -> Candle.builder()
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
                        .build())
                .toList();
    }
}
