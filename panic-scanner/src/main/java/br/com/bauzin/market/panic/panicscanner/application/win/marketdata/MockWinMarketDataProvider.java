package br.com.bauzin.market.panic.panicscanner.application.win.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "win.market-data", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockWinMarketDataProvider implements WinMarketDataProvider {

    private final List<WinCandle> candles5m = candles("5m");
    private final List<WinCandle> candles1m = candles("1m");
    private volatile boolean started;
    private volatile boolean subscribed;
    private volatile String symbol = "WINV26";

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void stop() {
        started = false;
        subscribed = false;
    }

    @Override
    public void subscribe(String symbol) {
        this.symbol = symbol;
        subscribed = true;
    }

    @Override
    public List<WinCandle> loadHistory(String symbol, LocalDate date) {
        return candles5m;
    }

    @Override
    public List<WinCandle> getIntradayCandles(String contract, String timeframe) {
        return "1m".equalsIgnoreCase(timeframe) ? candles1m : candles5m;
    }

    @Override
    public WinFlowSnapshot getFlowSnapshot(String contract) {
        return WinFlowSnapshot.unavailable();
    }

    @Override
    public WinMarketDataStatus status() {
        WinCandle last = candles5m.get(candles5m.size() - 1);
        return new WinMarketDataStatus("mock", false, started, subscribed, symbol, last.endTime(), last.close(), candles1m.size(), last.endTime(), "Mock backend ativo");
    }

    private List<WinCandle> candles(String timeframe) {
        int minutes = "1m".equals(timeframe) ? 1 : 5;
        OffsetDateTime start = OffsetDateTime.parse("2026-09-08T10:00:00-03:00");
        java.util.ArrayList<WinCandle> candles = new java.util.ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(190000);
        for (int i = 0; i < 40; i++) {
            BigDecimal open = price;
            price = price.subtract(BigDecimal.valueOf(i % 6 < 4 ? 45 : -20));
            BigDecimal close = price;
            BigDecimal high = open.max(close).add(BigDecimal.valueOf(25));
            BigDecimal low = open.min(close).subtract(BigDecimal.valueOf(35));
            OffsetDateTime candleStart = start.plusMinutes((long) i * minutes);
            OffsetDateTime candleEnd = candleStart.plusMinutes(minutes);
            BigDecimal volume = BigDecimal.valueOf(1000 + i * 20L);
            candles.add(new WinCandle(candleEnd, open, high, low, close, volume, close.multiply(volume), 10 + i, candleStart, candleEnd));
        }
        return List.copyOf(candles);
    }
}
