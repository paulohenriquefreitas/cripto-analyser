package br.com.bauzin.market.panic.panicscanner.api;

import java.time.OffsetDateTime;

public record WinIngestionResponse(
        String contract,
        String timeframe,
        int acceptedCount,
        OffsetDateTime receivedAt) {
}
