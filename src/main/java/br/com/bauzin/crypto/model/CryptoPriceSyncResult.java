package br.com.bauzin.crypto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoPriceSyncResult {

    private String symbol;

    private String interval;

    private String source;

    private Instant startTime;

    private Instant latestStoredTimestamp;

    private int fetchedRecords;

    private int persistedRecords;
}
