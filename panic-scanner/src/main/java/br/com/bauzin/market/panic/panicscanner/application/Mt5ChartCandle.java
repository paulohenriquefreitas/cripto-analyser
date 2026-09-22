package br.com.bauzin.market.panic.panicscanner.application;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Adds a backend-calculated indicator without duplicating candle fields. */
public record Mt5ChartCandle(@JsonUnwrapped Mt5Candle candle, Double sma9, Double sma21, Double vwap, String vwapSession) {}
