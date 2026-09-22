package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient;
import java.io.IOException;
import br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GetMt5CandlesUseCase {
    private final Mt5ProcessClient client;

    private final Ta4jMt5Sma9Adapter sma9;

    public GetMt5CandlesUseCase(Mt5ProcessClient client, Ta4jMt5Sma9Adapter sma9) {
        this.client = client;
        this.sma9 = sma9;
    }

    public synchronized List<Mt5ChartCandle> execute() throws IOException, InterruptedException {
        List<Mt5Candle> candles = client.readCandles();
        List<Mt5ChartCandle> values = sma9.synchronize(candles);
        return List.copyOf(values.subList(Math.max(0, values.size() - 100), values.size()));
    }
}
