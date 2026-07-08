package br.com.bauzin.crypto.model;

import br.com.bauzin.crypto.enums.Direction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sequence {

    private Direction direction;

    private Integer length;

    private Instant startTime;

    private Instant endTime;

    private Integer startIndex;

    private Integer endIndex;
}
