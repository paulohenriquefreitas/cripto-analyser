package br.com.bauzin.market.panic.panicscanner.application.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;

/** Immutable observation of the actual old adapter, captured under its lock. */
public record ShadowObservation(String symbol, long timeMsc, double price,
                                IntrabarCandleSnapshot candle, Double sma9, Double sma21,
                                boolean freshAfterSync, long syncGeneration) {}
