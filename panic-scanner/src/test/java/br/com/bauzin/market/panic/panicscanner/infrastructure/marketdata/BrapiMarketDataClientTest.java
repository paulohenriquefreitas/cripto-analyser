package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrapiMarketDataClientTest {

    private static final Clock SESSION_OPEN_CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T15:51:00Z"),
            ZoneId.of("America/Sao_Paulo"));
    private static final Clock AFTER_CLOSE_CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T22:30:00Z"),
            ZoneId.of("America/Sao_Paulo"));

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void shouldIncludeTokenWhenConfigured() {
        BrapiMarketDataClient client = client("secret-token", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=PETR4&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc&token=secret-token"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andExpect(queryParam("token", "secret-token"))
                .andRespond(withSuccess("""
                        {"results":[{"symbol":"PETR4","data":{"historicalDataPrice":[
                        {"date":1767225600,"open":10,"high":11,"low":9,"close":10,"volume":100}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));

        client.getDailyCandles("PETR4", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4));

        server.verify();
    }

    @Test
    void shouldMapMissingTokenToAuthenticationRequired() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=WEGE3&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"MISSING_TOKEN\",\"details\":\"raw provider token body\"}"));

        assertThatThrownBy(() -> client.getDailyCandles("WEGE3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4)))
                .isInstanceOfSatisfying(MarketDataProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ProviderErrorCode.AUTHENTICATION_REQUIRED);
                    assertThat(exception.getMessage()).isEqualTo("Market data provider authentication is required");
                    assertThat(exception.getMessage()).doesNotContain("raw provider token body");
                });
    }

    @Test
    void shouldMapHttpUnauthorizedToAuthenticationRequired() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=WEGE3&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"raw auth failure\"}"));

        assertThatThrownBy(() -> client.getDailyCandles("WEGE3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4)))
                .isInstanceOfSatisfying(MarketDataProviderException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProviderErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void shouldMapHttpTooManyRequestsToRateLimitExceeded() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=WEGE3&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"raw rate limit body\"}"));

        assertThatThrownBy(() -> client.getDailyCandles("WEGE3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4)))
                .isInstanceOfSatisfying(MarketDataProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ProviderErrorCode.RATE_LIMIT_EXCEEDED);
                    assertThat(exception.getMessage()).isEqualTo("Market data provider rate limit exceeded");
                    assertThat(exception.getMessage()).doesNotContain("raw rate limit body");
                });
    }

    @Test
    void shouldMapHttpServerErrorToProviderUnavailable() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=WEGE3&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc"))
                .andRespond(withServerError().body("raw server error"));

        assertThatThrownBy(() -> client.getDailyCandles("WEGE3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4)))
                .isInstanceOfSatisfying(MarketDataProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ProviderErrorCode.PROVIDER_UNAVAILABLE);
                    assertThat(exception.getMessage()).isEqualTo("Market data provider is unavailable");
                    assertThat(exception.getMessage()).doesNotContain("raw server error");
                });
    }

    @Test
    void shouldMapEmptyProviderResultToTickerNotFound() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=NOPE3&interval=1d&startDate=2026-01-01&endDate=2026-08-04&sortOrder=asc"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getDailyCandles("NOPE3", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 4)))
                .isInstanceOfSatisfying(MarketDataProviderException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProviderErrorCode.TICKER_NOT_FOUND));
    }

    @Test
    void shouldMarkCurrentDateCandleAsIntradayPartialWhileSessionIsOpen() {
        BrapiMarketDataClient client = client("", SESSION_OPEN_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=PETR4&interval=1d&startDate=2026-08-04&endDate=2026-08-05&sortOrder=asc"))
                .andRespond(withSuccess("""
                        {"results":[{"symbol":"PETR4","data":{"historicalDataPrice":[
                        {"date":1785812400,"open":10,"high":11,"low":9,"close":10,"volume":100},
                        {"date":1785898800,"open":20,"high":21,"low":19,"close":20,"volume":200}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));

        var candles = client.getDailyCandles("PETR4", LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5));

        assertThat(candles).extracting("status")
                .containsExactly(
                        br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.CLOSED,
                        br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.INTRADAY_PARTIAL);
    }

    @Test
    void shouldKeepCurrentDatePartialAfterCloseWhenProviderCompletionIsNotConfirmed() {
        BrapiMarketDataClient client = client("", AFTER_CLOSE_CLOCK, false);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=PETR4&interval=1d&startDate=2026-08-05&endDate=2026-08-05&sortOrder=asc"))
                .andRespond(withSuccess("""
                        {"results":[{"symbol":"PETR4","data":{"historicalDataPrice":[
                        {"date":1785898800,"open":20,"high":21,"low":19,"close":20,"volume":200}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));

        var candles = client.getDailyCandles("PETR4", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5));

        assertThat(candles).singleElement()
                .extracting("status")
                .isEqualTo(br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.INTRADAY_PARTIAL);
    }

    @Test
    void shouldMarkCurrentDateClosedAfterCloseWhenProviderCompletionIsConfirmed() {
        BrapiMarketDataClient client = client("", AFTER_CLOSE_CLOCK, true);
        server.expect(requestTo("https://brapi.dev/api/v2/stocks/historical?symbols=PETR4&interval=1d&startDate=2026-08-05&endDate=2026-08-05&sortOrder=asc"))
                .andRespond(withSuccess("""
                        {"results":[{"symbol":"PETR4","data":{"historicalDataPrice":[
                        {"date":1785898800,"open":20,"high":21,"low":19,"close":20,"volume":200}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));

        var candles = client.getDailyCandles("PETR4", LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5));

        assertThat(candles).singleElement()
                .extracting("status")
                .isEqualTo(br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.CLOSED);
    }

    private BrapiMarketDataClient client(String token, Clock clock, boolean currentDateFinalizedAfterClose) {
        return new BrapiMarketDataClient(
                builder,
                new BrapiProperties("https://brapi.dev", token, LocalTime.of(18, 0), currentDateFinalizedAfterClose),
                clock);
    }
}
