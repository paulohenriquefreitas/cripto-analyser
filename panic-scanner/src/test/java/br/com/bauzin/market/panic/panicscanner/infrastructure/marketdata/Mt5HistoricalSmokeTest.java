package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in integration check against the connected terminal; never runs in ordinary builds. */
@EnabledIfSystemProperty(named = "mt5.smoke", matches = "true")
class Mt5HistoricalSmokeTest {
    @Test
    void readsHistoricalB3D1ThroughJavaPythonAdapter() {
        var brapi = mock(BrapiMarketDataClient.class);
        var client = new Mt5MarketDataClient(brapi, new Mt5ProcessClient(), Clock.systemUTC());
        var from = LocalDate.of(2026, 7, 1);
        var to = LocalDate.of(2026, 10, 6);
        for (String ticker : List.of("MGLU3", "PETR4", "PRIO3", "VALE3")) {
            var candles = client.getDailyCandles(ticker, from, to);
            assertTrue(candles.size() >= 21, ticker);
            assertFalse(candles.getFirst().date().isBefore(from));
            assertEquals(to, candles.getLast().date(), ticker);
            assertTrue(candles.getLast().volume().signum() > 0, ticker);
            System.out.printf("MT5_SMOKE %s count=%d first=%s last=%s close=%s realVolume=%s%n",
                    ticker, candles.size(), candles.getFirst().date(), candles.getLast().date(),
                    candles.getLast().close(), candles.getLast().volume());
        }
        verifyNoInteractions(brapi);
    }
}
