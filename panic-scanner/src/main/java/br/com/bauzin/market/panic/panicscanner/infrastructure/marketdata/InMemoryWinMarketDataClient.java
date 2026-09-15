package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.WinMarketDataClient;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryWinMarketDataClient implements WinMarketDataClient {

    private final Map<String, List<WinCandle>> candlesByContractAndTimeframe = new ConcurrentHashMap<>();
    private final Map<String, WinFlowSnapshot> flowByContract = new ConcurrentHashMap<>();

    @Override
    public List<WinCandle> getIntradayCandles(String contract, String timeframe) {
        return candlesByContractAndTimeframe.getOrDefault(key(contract, timeframe), List.of());
    }

    @Override
    public WinFlowSnapshot getFlowSnapshot(String contract) {
        return flowByContract.getOrDefault(normalize(contract), WinFlowSnapshot.unavailable());
    }

    public void replaceCandles(String contract, String timeframe, List<WinCandle> candles) {
        candlesByContractAndTimeframe.put(key(contract, timeframe), candles.stream()
                .sorted(Comparator.comparing(WinCandle::timestamp))
                .toList());
    }

    public void appendCandles(String contract, String timeframe, List<WinCandle> candles) {
        String key = key(contract, timeframe);
        List<WinCandle> merged = java.util.stream.Stream.concat(
                        candlesByContractAndTimeframe.getOrDefault(key, List.of()).stream(),
                        candles.stream())
                .collect(java.util.stream.Collectors.toMap(
                        candle -> candle.timestamp().toInstant(),
                        candle -> candle,
                        (previous, current) -> current))
                .values()
                .stream()
                .sorted(Comparator.comparing(WinCandle::timestamp))
                .toList();
        candlesByContractAndTimeframe.put(key, merged);
    }

    public void updateFlow(String contract, WinFlowSnapshot flow) {
        flowByContract.put(normalize(contract), flow);
    }

    private String key(String contract, String timeframe) {
        return normalize(contract) + ":" + normalize(timeframe);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
