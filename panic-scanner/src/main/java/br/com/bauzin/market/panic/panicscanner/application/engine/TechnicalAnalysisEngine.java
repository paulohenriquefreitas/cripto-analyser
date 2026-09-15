package br.com.bauzin.market.panic.panicscanner.application.engine;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Application port for technical analysis calculation engines. */
public interface TechnicalAnalysisEngine {

    int minimumRequiredCandles();

    TechnicalAnalysis analyze(String ticker, String timeframe, List<Candle> candles);

    default Set<TechnicalIndicator> indicators() {
        return EnumSet.of(
                TechnicalIndicator.SMA,
                TechnicalIndicator.RSI,
                TechnicalIndicator.EMA,
                TechnicalIndicator.ATR,
                TechnicalIndicator.MACD,
                TechnicalIndicator.ADX,
                TechnicalIndicator.VWAP,
                TechnicalIndicator.BOLLINGER,
                TechnicalIndicator.VOLUME,
                TechnicalIndicator.SUPPORT,
                TechnicalIndicator.RESISTANCE);
    }
}
