package br.com.bauzin.crypto.service;

import br.com.bauzin.crypto.analyzer.SequenceAnalyzer;
import br.com.bauzin.crypto.analyzer.TrendAnalyzer;
import br.com.bauzin.crypto.model.Candle;
import br.com.bauzin.crypto.model.CryptoPriceSyncResult;
import br.com.bauzin.crypto.model.SequenceResult;
import br.com.bauzin.crypto.model.TrendAnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final SequenceAnalyzer sequenceAnalyzer;
    private final TrendAnalyzer trendAnalyzer;
    private final CryptoPriceHistoryService cryptoPriceHistoryService;

    /**
     * Retorna os candles obtidos da corretora.
     */
    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Integer requestedCandles) {

        return cryptoPriceHistoryService.getCandles(symbol, interval, requestedCandles);
    }

    public List<Candle> getCandles(String symbol,
                                   String interval,
                                   Integer requestedCandles,
                                   String loxCookie) {

        return cryptoPriceHistoryService.getCandles(symbol, interval, requestedCandles, loxCookie);
    }

    public CryptoPriceSyncResult syncPrices(String symbol) {

        return cryptoPriceHistoryService.sync(symbol);
    }

    public CryptoPriceSyncResult syncPrices(String symbol, String interval) {

        return cryptoPriceHistoryService.sync(symbol, interval);
    }

    public CryptoPriceSyncResult syncPrices(String symbol, String interval, Integer requestedCandles) {

        return cryptoPriceHistoryService.sync(symbol, interval, requestedCandles);
    }

    /**
     * Analisa as sequências de alta e baixa.
     */
    public SequenceResult analyzeSequences(String symbol,
                                           String interval,
                                           Integer requestedCandles) {
        return analyzeSequences(symbol, interval, requestedCandles, null);
    }

    public SequenceResult analyzeSequences(String symbol,
                                           String interval,
                                           Integer requestedCandles,
                                           String loxCookie) {

        List<Candle> candles = getCandles(symbol, interval, requestedCandles, loxCookie);

        return sequenceAnalyzer.analyze(candles);
    }

    /**
     * TODO
     * Detectar tendência.
     */
    public TrendAnalysisResult analyzeTrend(String symbol,
                                            String interval,
                                            Integer requestedCandles) {
        return analyzeTrend(symbol, interval, requestedCandles, null);
    }

    public TrendAnalysisResult analyzeTrend(String symbol,
                                            String interval,
                                            Integer requestedCandles,
                                            String loxCookie) {

        List<Candle> candles = getCandles(symbol, interval, requestedCandles, loxCookie);

        return trendAnalyzer.analyze(candles);
    }

    /**
     * TODO
     * Detectar mercado lateral (Flat).
     */
    public Object analyzeFlat(String symbol,
                              String interval,
                              Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * TODO
     * Calcular probabilidades estatísticas.
     */
    public Object analyzeProbability(String symbol,
                                     String interval,
                                     Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * TODO
     * Executar backtest da estratégia.
     */
    public Object backtest(String symbol,
                           String interval,
                           Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * TODO
     * Estatísticas por horário.
     */
    public Object hourlyStatistics(String symbol,
                                   String interval,
                                   Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * TODO
     * Estatísticas por dia da semana.
     */
    public Object weekDayStatistics(String symbol,
                                    String interval,
                                    Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * TODO
     * Calcular indicadores técnicos.
     */
    public Object indicators(String symbol,
                             String interval,
                             Integer limit) {

        throw new UnsupportedOperationException("Not implemented yet");
    }

}
