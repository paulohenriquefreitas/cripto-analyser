package br.com.bauzin.market.panic.panicscanner.domain.strategy;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.TechnicalAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.analysis.Trend;
import br.com.bauzin.market.panic.panicscanner.domain.checks.TechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryAnalysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Immutable response model returned by scanner strategies and exposed by REST. */
public record ScannerResult(
        String ticker,
        LocalDate analysisDate,
        String timeframe,
        BigDecimal lastClose,
        BigDecimal currentPrice,
        CandleStatus dataStatus,
        Candle currentPartialCandle,
        OffsetDateTime marketDataUpdatedAt,
        Integer score,
        SignalStatus status,
        Trend trend,
        TechnicalAnalysis technicalAnalysis,
        EntryAnalysis entryAnalysis,
        TechnicalChecks checks,
        List<String> reasons) {

    public ScannerResult(String ticker,
                         LocalDate analysisDate,
                         String timeframe,
                         BigDecimal lastClose,
                         Integer score,
                         SignalStatus status,
                         Trend trend,
                         TechnicalAnalysis technicalAnalysis,
                         TechnicalChecks checks,
                         List<String> reasons) {
        this(ticker,
                analysisDate,
                timeframe,
                lastClose,
                lastClose,
                br.com.bauzin.market.panic.panicscanner.domain.analysis.CandleStatus.CLOSED,
                null,
                null,
                score,
                status,
                trend,
                technicalAnalysis,
                null,
                checks,
                reasons);
    }

    public ScannerResult {
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        reasons = List.copyOf(reasons);
    }

    public ScannerResult withMarketDataSnapshot(BigDecimal currentPrice,
                                                CandleStatus dataStatus,
                                                Candle currentPartialCandle,
                                                OffsetDateTime marketDataUpdatedAt) {
        return new ScannerResult(
                ticker,
                analysisDate,
                timeframe,
                lastClose,
                currentPrice,
                dataStatus,
                currentPartialCandle,
                marketDataUpdatedAt,
                score,
                status,
                trend,
                technicalAnalysis,
                entryAnalysis,
                checks,
                reasons);
    }

    public ScannerResult withEntryAnalysis(EntryAnalysis entryAnalysis) {
        return new ScannerResult(
                ticker,
                analysisDate,
                timeframe,
                lastClose,
                currentPrice,
                dataStatus,
                currentPartialCandle,
                marketDataUpdatedAt,
                score,
                status,
                trend,
                technicalAnalysis,
                entryAnalysis,
                checks,
                reasons);
    }
}
