package br.com.bauzin.crypto.model;

import br.com.bauzin.crypto.enums.Direction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalAlert {

    private String symbol;

    private String assetName;

    private String interval;

    private Direction direction;

    private String suggestedAction;

    private Integer closedSequenceLength;

    private Integer secondsSinceSignal;

    private Instant signalCandleOpenTime;

    private Instant signalCandleCloseTime;

    private String alertKey;

    private String message;

    private List<ReversalAlertCandle> signalCandles;
}
