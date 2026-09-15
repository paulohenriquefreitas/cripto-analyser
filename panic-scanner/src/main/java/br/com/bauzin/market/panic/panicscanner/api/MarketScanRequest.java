package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryStatus;
import br.com.bauzin.market.panic.panicscanner.domain.entry.RankingMode;

import java.math.BigDecimal;
import java.util.List;

/** Optional overrides for the automatic B3 market scan. */
public record MarketScanRequest(
        BigDecimal minimumAverageFinancialVolume,
        Integer minimumScore,
        Integer minimumMomentumScore,
        Integer minimumEntryScore,
        List<EntryStatus> entryStatuses,
        RankingMode rankingMode,
        Integer limit) {
}
