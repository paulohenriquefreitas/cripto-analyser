package br.com.bauzin.market.panic.panicscanner.domain.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureSample;
import java.util.Optional;

/** completedCandle is present only on rollover; no history of ticks is retained. */
public record IntrabarMarketState(String symbol, long timeMsc, double price,
                                 IntrabarCandleSnapshot candle, Double sma9, Double sma21,
                                 IntrabarCandleSnapshot completedCandle) {
    public Optional<MarketStructureSample> structureSample() {
        return sma9 == null || sma21 == null ? Optional.empty()
                : Optional.of(new MarketStructureSample(symbol, timeMsc, price, sma9, sma21));
    }
}
