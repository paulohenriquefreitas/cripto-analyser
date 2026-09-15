package br.com.bauzin.market.panic.panicscanner.application.usecase;

import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;

import java.math.BigDecimal;
import java.util.List;

/** Runtime criteria for a controlled B3 market momentum scan. */
public record MarketScanCriteria(
        BigDecimal minimumAverageFinancialVolume,
        int minimumScore,
        int minimumEntryScore,
        List<EntryStatus> entryStatuses,
        RankingMode rankingMode,
        int limit) {

    public MarketScanCriteria(BigDecimal minimumAverageFinancialVolume, int minimumScore, int limit) {
        this(minimumAverageFinancialVolume, minimumScore, 0, List.of(), RankingMode.MOMENTUM, limit);
    }

    public MarketScanCriteria {
        entryStatuses = entryStatuses == null ? List.of() : List.copyOf(entryStatuses);
        rankingMode = rankingMode == null ? RankingMode.MOMENTUM : rankingMode;
    }
}
