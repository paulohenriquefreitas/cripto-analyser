package br.com.bauzin.market.panic.panicscanner.application.win.candle;

import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class WinCandleBuilder {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");

    private final Duration timeframe;
    private CurrentCandle current;

    public WinCandleBuilder(Duration timeframe) {
        this.timeframe = Objects.requireNonNull(timeframe, "timeframe must not be null");
    }

    public synchronized List<WinCandle> onTrade(WinTrade trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        LocalDateTime windowStart = windowStart(trade.timestamp());
        if (current == null) {
            current = CurrentCandle.open(windowStart, windowEnd(windowStart), trade);
            return List.of();
        }
        if (windowStart.equals(current.startTime)) {
            current.update(trade);
            return List.of();
        }
        List<WinCandle> closed = new ArrayList<>();
        closed.add(current.toCandle());
        current = CurrentCandle.open(windowStart, windowEnd(windowStart), trade);
        return closed;
    }

    public synchronized WinCandle currentCandle() {
        return current == null ? null : current.toCandle();
    }

    private LocalDateTime windowStart(LocalDateTime timestamp) {
        long minute = timestamp.getMinute();
        long bucket = minute - minute % timeframe.toMinutes();
        return timestamp.withMinute((int) bucket).withSecond(0).withNano(0);
    }

    private LocalDateTime windowEnd(LocalDateTime start) {
        return start.plus(timeframe);
    }

    private static OffsetDateTime offset(LocalDateTime dateTime) {
        return dateTime.atZone(MARKET_ZONE).toOffsetDateTime();
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static final class CurrentCandle {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
        private BigDecimal financialVolume;
        private long tradeCount;

        private CurrentCandle(LocalDateTime startTime, LocalDateTime endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }

        private static CurrentCandle open(LocalDateTime startTime, LocalDateTime endTime, WinTrade trade) {
            CurrentCandle candle = new CurrentCandle(startTime, endTime);
            BigDecimal price = decimal(trade.price());
            candle.open = price;
            candle.high = price;
            candle.low = price;
            candle.close = price;
            candle.volume = decimal(trade.quantity());
            candle.financialVolume = decimal(trade.financialVolume());
            candle.tradeCount = 1;
            return candle;
        }

        private void update(WinTrade trade) {
            BigDecimal price = decimal(trade.price());
            close = price;
            high = high.max(price);
            low = low.min(price);
            volume = volume.add(decimal(trade.quantity()));
            financialVolume = financialVolume.add(decimal(trade.financialVolume()));
            tradeCount++;
        }

        private WinCandle toCandle() {
            return new WinCandle(
                    offset(endTime),
                    open,
                    high,
                    low,
                    close,
                    volume,
                    financialVolume,
                    tradeCount,
                    offset(startTime),
                    offset(endTime));
        }
    }
}
