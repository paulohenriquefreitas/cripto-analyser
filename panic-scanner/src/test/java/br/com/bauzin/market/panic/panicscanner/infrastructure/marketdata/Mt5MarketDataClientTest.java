package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.io.IOException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Mt5MarketDataClientTest {
    private final BrapiMarketDataClient brapi = mock(BrapiMarketDataClient.class);
    private final Mt5ProcessClient mt5 = mock(Mt5ProcessClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZoneOffset.UTC);
    private final Mt5MarketDataClient client = new Mt5MarketDataClient(brapi, mt5, clock);
    private final LocalDate from = LocalDate.of(2026, 10, 5);
    private final LocalDate to = LocalDate.of(2026, 10, 6);

    private Mt5Candle rate(LocalDate date, long realVolume) {
        return new Mt5Candle(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 10, 12, 9, 11, 99, realVolume);
    }

    @Test
    void discoveryDelegatesOnlyToBrapi() {
        var stocks = List.of(new StockSummary("PETR4", "Petrobras", "STOCK", true));
        when(brapi.listStocks()).thenReturn(stocks);
        assertSame(stocks, client.listStocks());
        verify(brapi).listStocks();
        verifyNoInteractions(mt5);
    }

    @Test
    void preservesUtcSessionSortsBoundsAndMapsRealVolume() throws Exception {
        when(mt5.readCandles("PETR4", "D1", from, to)).thenReturn(List.of(
                rate(to, 1000), rate(from.minusDays(1), 1), rate(from, 2000), rate(to.plusDays(1), 1)));
        var candles = client.getDailyCandles("PETR4", from, to);
        assertEquals(List.of(from, to), candles.stream().map(c -> c.date()).toList());
        assertEquals(1000, candles.getLast().volume().longValueExact());
        assertEquals(11000, candles.getLast().financialVolume().longValueExact());
        assertEquals(CandleStatus.CLOSED, candles.getLast().status());
        verify(mt5).readCandles("PETR4", "D1", from, to);
        verifyNoInteractions(brapi);
    }

    @Test
    void zeroRealVolumeNeverFallsBackToTickVolume() throws Exception {
        when(mt5.readCandles("PETR4", "D1", from, to)).thenReturn(List.of(rate(from, 0)));
        assertEquals(0, client.getDailyCandles("PETR4", from, to).getFirst().volume().signum());
    }

    @Test
    void currentSessionIsPartialAndFutureSessionsAreExcluded() throws Exception {
        var today = to.plusDays(1);
        when(mt5.readCandles("PETR4", "D1", from, today.plusDays(1)))
                .thenReturn(List.of(rate(today, 100), rate(today.plusDays(1), 100)));
        var candles = client.getDailyCandles("PETR4", from, today.plusDays(1));
        assertEquals(1, candles.size());
        assertEquals(today, candles.getFirst().date());
        assertEquals(CandleStatus.INTRADAY_PARTIAL, candles.getFirst().status());
    }

    @Test
    void rejectsNegativeVolumeDuplicateSessionsAndInvalidOhlc() throws Exception {
        for (var rates : List.of(List.of(rate(from, -1)), List.of(rate(from, 1), rate(from, 2)),
                List.of(new Mt5Candle(rate(from, 1).time(), 15, 12, 9, 11, 99, 10)))) {
            when(mt5.readCandles("PETR4", "D1", from, to)).thenReturn(rates);
            var error = assertThrows(MarketDataProviderException.class, () -> client.getDailyCandles("PETR4", from, to));
            assertEquals(ProviderErrorCode.INVALID_PROVIDER_RESPONSE, error.errorCode());
        }
    }

    @Test
    void mapsProcessFailureAndPreservesInterrupt() throws Exception {
        when(mt5.readCandles("PETR4", "D1", from, to)).thenThrow(new IOException("offline"));
        assertEquals(ProviderErrorCode.PROVIDER_UNAVAILABLE,
                assertThrows(MarketDataProviderException.class, () -> client.getDailyCandles("PETR4", from, to)).errorCode());
        doThrow(new InterruptedException()).when(mt5).readCandles("PETR4", "D1", from, to);
        try {
            assertThrows(MarketDataProviderException.class, () -> client.getDailyCandles("PETR4", from, to));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void reversedIntervalNeverCallsProviders() {
        assertThrows(IllegalArgumentException.class, () -> client.getDailyCandles("PETR4", to, from));
        verifyNoInteractions(mt5, brapi);
    }

    @Test
    void brapiIsDefaultAndMt5IsOptInPrimary() {
        var context = new ApplicationContextRunner()
                .withBean(BrapiMarketDataClient.class, () -> brapi)
                .withBean(Mt5ProcessClient.class, () -> mt5)
                .withBean(Clock.class, () -> clock)
                .withUserConfiguration(Mt5MarketDataClient.class);
        context.run(c -> {
            assertNull(c.getStartupFailure());
            assertSame(brapi, c.getBean(MarketDataClient.class));
            assertFalse(c.containsBean("mt5MarketDataClient"));
        });
        context.withPropertyValues("panic-scanner.historical.provider=mt5").run(c -> {
            assertNull(c.getStartupFailure());
            assertInstanceOf(Mt5MarketDataClient.class, c.getBean(MarketDataClient.class));
            assertSame(brapi, c.getBean(BrapiMarketDataClient.class));
        });
    }
}
