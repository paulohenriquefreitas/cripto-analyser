package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataClient;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.StockSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Historical D1 adapter; the equity universe remains owned by BRAPI. */
@Component
@Primary
@ConditionalOnProperty(name = "panic-scanner.historical.provider", havingValue = "mt5")
public class Mt5MarketDataClient implements MarketDataClient {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private final BrapiMarketDataClient brapi;
    private final Mt5ProcessClient mt5;
    private final Clock clock;

    public Mt5MarketDataClient(BrapiMarketDataClient brapi, Mt5ProcessClient mt5, Clock clock) {
        this.brapi = brapi;
        this.mt5 = mt5;
        this.clock = clock;
    }

    @Override
    public List<StockSummary> listStocks() {
        return brapi.listStocks();
    }

    @Override
    public List<Candle> getDailyCandles(String ticker, LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
        List<Mt5Candle> rates;
        try {
            rates = mt5.readCandles(ticker, "D1", from, to);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketDataProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE);
        } catch (IOException exception) {
            throw new MarketDataProviderException(ProviderErrorCode.PROVIDER_UNAVAILABLE);
        }
        if (rates == null) throw invalidResponse();
        List<Candle> result = new ArrayList<>();
        LocalDate today = LocalDate.now(clock.withZone(MARKET_ZONE));
        OffsetDateTime fetchedAt = OffsetDateTime.now(clock);
        for (Mt5Candle rate : rates) {
            if (rate == null || rate.time() < 0) throw invalidResponse();
            // This feed labels D1 sessions at UTC midnight: converting to Sao Paulo loses a day.
            LocalDate session = Instant.ofEpochSecond(rate.time()).atZone(ZoneOffset.UTC).toLocalDate();
            if (session.isBefore(from) || session.isAfter(to) || session.isAfter(today)) continue;
            if (!Double.isFinite(rate.open()) || !Double.isFinite(rate.high())
                    || !Double.isFinite(rate.low()) || !Double.isFinite(rate.close())
                    || rate.low() <= 0 || rate.high() < rate.low()
                    || rate.open() < rate.low() || rate.open() > rate.high()
                    || rate.close() < rate.low() || rate.close() > rate.high()
                    || rate.realVolume() < 0 || rate.tickVolume() < 0) throw invalidResponse();
            // Today's bar is conservatively partial; historical sessions are closed.
            result.add(new Candle(session, BigDecimal.valueOf(rate.open()), BigDecimal.valueOf(rate.high()),
                    BigDecimal.valueOf(rate.low()), BigDecimal.valueOf(rate.close()),
                    BigDecimal.valueOf(rate.realVolume()),
                    session.isBefore(today) ? CandleStatus.CLOSED : CandleStatus.INTRADAY_PARTIAL, fetchedAt));
        }
        result.sort(Comparator.comparing(Candle::date));
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i - 1).date().equals(result.get(i).date())) throw invalidResponse();
        }
        return List.copyOf(result);
    }

    private static MarketDataProviderException invalidResponse() {
        return new MarketDataProviderException(ProviderErrorCode.INVALID_PROVIDER_RESPONSE);
    }
}
