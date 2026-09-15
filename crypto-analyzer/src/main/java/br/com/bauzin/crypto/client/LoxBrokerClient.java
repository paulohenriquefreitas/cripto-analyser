package br.com.bauzin.crypto.client;

import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.SupportedAsset;
import br.com.bauzin.crypto.service.CandleIntervalHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LoxBrokerClient {

    private static final String SOURCE = "LOXBROKER";
    private static final int MAX_PAGE_SIZE = 5000;

    private final RestClient restClient;

    public LoxBrokerClient(RestClient.Builder builder) {
        this.restClient = builder
                .baseUrl("https://loxbroker.com")
                .build();
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   int requestedCandles,
                                   String loxCookie) {
        int safeRequestedCandles = normalizeCountback(requestedCandles);
        String normalizedInterval = CandleIntervalHelper.normalize(interval);
        long intervalMillis = CandleIntervalHelper.toMillis(normalizedInterval);
        Instant currentTo = CandleIntervalHelper.resolveLatestClosedOpenTime(normalizedInterval);
        List<Candle> collected = new ArrayList<>();

        while (collected.size() < safeRequestedCandles) {
            int pageSize = Math.min(MAX_PAGE_SIZE, safeRequestedCandles - collected.size());
            Instant currentFrom = Instant.ofEpochMilli(
                    Math.max(0, currentTo.toEpochMilli() - (intervalMillis * Math.max(pageSize - 1L, 0L))));

            List<Candle> page = getCandles(symbol, normalizedInterval, currentFrom, currentTo, pageSize, loxCookie);

            if (page.isEmpty()) {
                break;
            }

            collected.addAll(page);

            Instant earliestOpenTime = page.getFirst().getOpenTime();
            Instant nextTo = earliestOpenTime.minusMillis(intervalMillis);

            if (!nextTo.isBefore(earliestOpenTime)) {
                break;
            }

            currentTo = nextTo;
        }

        List<Candle> ordered = collected.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Candle::getOpenTime,
                        candle -> candle,
                        (left, right) -> right,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .sorted(java.util.Comparator.comparing(Candle::getOpenTime))
                .toList();

        int skipCount = Math.max(0, ordered.size() - safeRequestedCandles);
        return ordered.stream()
                .skip(skipCount)
                .toList();
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Instant from,
                                   Instant to,
                                   Integer countback) {
        return getCandles(symbol, interval, from, to, countback, null);
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Instant from,
                                   Instant to,
                                   Integer countback,
                                   String loxCookie) {

        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/publicapi/tradingview/udf-history")
                        .queryParam("symbol", normalizeSymbol(symbol))
                        .queryParam("resolution", toResolution(interval))
                        .queryParam("from", from.getEpochSecond())
                        .queryParam("to", to.getEpochSecond())
                        .queryParam("countback", normalizeCountback(countback))
                        .queryParam("site", "loxbroker.com")
                        .build());

        if (loxCookie != null && !loxCookie.isBlank()) {
            request = request.header("Cookie", loxCookie.trim());
        }

        LoxBrokerUdfResponse response = request.retrieve()
                .body(LoxBrokerUdfResponse.class);

        return toCandles(response, interval);
    }

    private List<Candle> toCandles(LoxBrokerUdfResponse response, String interval) {
        if (response == null || response.t() == null || response.t().isEmpty()) {
            return List.of();
        }

        int size = response.t().size();
        List<Candle> candles = new ArrayList<>(size);
        long intervalMillis = CandleIntervalHelper.toMillis(interval);

        for (int index = 0; index < size; index++) {
            Instant openTime = Instant.ofEpochSecond(response.t().get(index));
            candles.add(Candle.builder()
                    .openTime(openTime)
                    .closeTime(openTime.plusMillis(intervalMillis - 1))
                    .open(new BigDecimal(response.o().get(index)))
                    .high(new BigDecimal(response.h().get(index)))
                    .low(new BigDecimal(response.l().get(index)))
                    .close(new BigDecimal(response.c().get(index)))
                    .volume(toBigDecimal(response.v(), index))
                    .source(SOURCE)
                    .build());
        }

        return candles;
    }

    private BigDecimal toBigDecimal(List<String> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(values.get(index));
    }

    private String normalizeSymbol(String symbol) {
        return SupportedAsset.normalize(symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol);
    }

    private int normalizeCountback(Integer countback) {
        return countback == null || countback < 1 ? 300 : countback;
    }

    private String toResolution(String interval) {
        return switch (CandleIntervalHelper.normalize(interval)) {
            case "1m" -> "1";
            case "5m" -> "5";
            case "15m" -> "15";
            case "1h" -> "60";
            case "4h" -> "240";
            case "1d" -> "1D";
            default -> throw new IllegalArgumentException("Unsupported Lox Broker interval: " + interval);
        };
    }
}
