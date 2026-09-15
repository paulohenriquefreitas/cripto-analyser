package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Component
public class BrapiMarketDataClient implements MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(BrapiMarketDataClient.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final String LIST_STOCKS_ENDPOINT = "/api/quote/list";
    private static final String HISTORICAL_ENDPOINT = "/api/v2/stocks/historical";
    private static final int STOCK_LIST_LIMIT = 1000;

    private final RestClient restClient;
    private final BrapiProperties properties;
    private final Clock clock;

    public BrapiMarketDataClient(RestClient.Builder builder, BrapiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        RestClient.Builder configuredBuilder = builder.baseUrl(properties.baseUrl());
        if (hasToken()) {
            configuredBuilder.defaultHeader("Authorization", "Bearer " + properties.token());
        }
        this.restClient = configuredBuilder.build();
    }

    @Override
    public List<StockSummary> listStocks() {
        QuoteListResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> addToken(uriBuilder
                            .path(LIST_STOCKS_ENDPOINT)
                            .queryParam("type", "stock")
                            .queryParam("limit", STOCK_LIST_LIMIT))
                            .build())
                    .retrieve()
                    .body(QuoteListResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapProviderException("LIST", LIST_STOCKS_ENDPOINT, exception);
        } catch (ResourceAccessException exception) {
            log.warn("BRAPI provider unavailable ticker={} endpoint={}", "LIST", LIST_STOCKS_ENDPOINT, exception);
            throw new MarketDataProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE);
        }

        if (response == null || response.stocks() == null) {
            log.warn("BRAPI invalid response ticker={} endpoint={} status={}", "LIST", LIST_STOCKS_ENDPOINT, "200");
            throw new MarketDataProviderException(ProviderErrorCode.INVALID_PROVIDER_RESPONSE);
        }

        return response.stocks().stream()
                .filter(stock -> stock.stock() != null)
                .map(stock -> new StockSummary(stock.stock(), stock.name(), stock.type(), stock.active()))
                .toList();
    }

    @Override
    public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
        StockHistoricalResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> addToken(uriBuilder
                            .path(HISTORICAL_ENDPOINT)
                            .queryParam("symbols", ticker)
                            .queryParam("interval", "1d")
                            .queryParam("startDate", from)
                            .queryParam("endDate", to)
                            .queryParam("sortOrder", "asc"))
                            .build())
                    .retrieve()
                    .body(StockHistoricalResponse.class);
        } catch (RestClientResponseException exception) {
            throw mapProviderException(ticker, HISTORICAL_ENDPOINT, exception);
        } catch (ResourceAccessException exception) {
            log.warn("BRAPI provider unavailable ticker={} endpoint={}", ticker, HISTORICAL_ENDPOINT, exception);
            throw new MarketDataProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE);
        }

        if (response == null || response.results() == null || response.results().isEmpty()) {
            log.warn("BRAPI ticker not found ticker={} endpoint={} status={}", ticker, HISTORICAL_ENDPOINT, "200");
            throw new MarketDataProviderException(ProviderErrorCode.TICKER_NOT_FOUND);
        }

        StockHistoricalResult firstResult = response.results().get(0);
        if (firstResult.data() == null || firstResult.data().historicalDataPrice() == null) {
            log.warn("BRAPI invalid response ticker={} endpoint={} status={}", ticker, HISTORICAL_ENDPOINT, "200");
            throw new MarketDataProviderException(ProviderErrorCode.INVALID_PROVIDER_RESPONSE);
        }

        OffsetDateTime fetchedAt = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        return firstResult.data().historicalDataPrice().stream()
                .filter(StockHistoricalPrice::hasOhlcv)
                .map(price -> toCandle(ticker, price, fetchedAt))
                .toList();
    }

    private UriBuilder addToken(UriBuilder uriBuilder) {
        if (hasToken()) {
            return uriBuilder.queryParam("token", properties.token());
        }
        return uriBuilder;
    }

    private boolean hasToken() {
        return properties.token() != null && !properties.token().isBlank();
    }

    private MarketDataProviderException mapProviderException(String ticker,
                                                             String endpoint,
                                                             RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        String providerBody = exception.getResponseBodyAsString();
        ProviderErrorCode errorCode = mapProviderError(statusCode, providerBody);
        log.warn("BRAPI error ticker={} endpoint={} status={} providerBody={}",
                ticker, endpoint, statusCode, providerBody);
        return new MarketDataProviderException(errorCode);
    }

    private ProviderErrorCode mapProviderError(int statusCode, String providerBody) {
        String normalizedBody = providerBody == null ? "" : providerBody.toUpperCase();
        if (statusCode == 401 || statusCode == 403 || normalizedBody.contains("MISSING_TOKEN")) {
            return ProviderErrorCode.AUTHENTICATION_REQUIRED;
        }
        if (statusCode == 429) {
            return ProviderErrorCode.RATE_LIMIT_EXCEEDED;
        }
        if (statusCode == 404 || normalizedBody.contains("NOT_FOUND") || normalizedBody.contains("NOT FOUND")) {
            return ProviderErrorCode.TICKER_NOT_FOUND;
        }
        if (statusCode >= 500) {
            return ProviderErrorCode.PROVIDER_UNAVAILABLE;
        }
        return ProviderErrorCode.INVALID_PROVIDER_RESPONSE;
    }

    private Candle toCandle(String ticker, StockHistoricalPrice price, OffsetDateTime fetchedAt) {
        LocalDate date = Instant.ofEpochSecond(price.date()).atZone(MARKET_ZONE).toLocalDate();
        return new Candle(
                date,
                price.open(),
                price.high(),
                price.low(),
                price.close(),
                price.volume(),
                inferStatus(ticker, date),
                fetchedAt);
    }

    private CandleStatus inferStatus(String ticker, LocalDate tradeDate) {
        LocalDate currentDate = LocalDate.now(clock.withZone(MARKET_ZONE));
        if (tradeDate.isAfter(currentDate)) {
            log.warn("BRAPI future candle date ticker={} endpoint={} status={} tradeDate={}",
                    ticker, HISTORICAL_ENDPOINT, "200", tradeDate);
            throw new MarketDataProviderException(ProviderErrorCode.INVALID_PROVIDER_RESPONSE);
        }
        if (tradeDate.isBefore(currentDate)) {
            return CandleStatus.CLOSED;
        }

        LocalTime currentTime = LocalTime.now(clock.withZone(MARKET_ZONE));
        if (currentTime.isBefore(properties.marketCloseTime())) {
            return CandleStatus.INTRADAY_PARTIAL;
        }
        return properties.currentDateCandleFinalizedAfterMarketClose()
                ? CandleStatus.CLOSED
                : CandleStatus.INTRADAY_PARTIAL;
    }

    record QuoteListResponse(List<QuoteListItem> stocks) {
    }

    record QuoteListItem(String stock, String name, String type, Boolean active) {
    }

    record StockHistoricalResponse(List<StockHistoricalResult> results) {
    }

    record StockHistoricalResult(String requestedSymbol, String symbol, StockHistoricalSeries data) {
    }

    record StockHistoricalSeries(String usedInterval, String usedRange, List<StockHistoricalPrice> historicalDataPrice) {
    }

    record StockHistoricalPrice(
            long date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal adjustedClose) {

        boolean hasOhlcv() {
            return Objects.nonNull(open)
                    && Objects.nonNull(high)
                    && Objects.nonNull(low)
                    && Objects.nonNull(close)
                    && Objects.nonNull(volume);
        }
    }
}
