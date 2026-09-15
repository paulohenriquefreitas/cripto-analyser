package br.com.bauzin.crypto.model;

import br.com.bauzin.crypto.enums.Direction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalAlertCandle {

    private Instant openTime;

    private Instant closeTime;

    private BigDecimal open;

    private BigDecimal close;

    private Direction direction;
}
