package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;

/** BUY/SELL are source flags, never inferred from a price movement. */
public final class Mt5AggressorMapper {
    private Mt5AggressorMapper() {}
    public static AggressorSide side(int flags) {
        if (flags < 0 || (flags & ~126) != 0 || (flags & 24) == 0) return AggressorSide.AMBIGUOUS;
        boolean buy = (flags & 32) != 0;
        boolean sell = (flags & 64) != 0;
        return buy == sell ? AggressorSide.AMBIGUOUS : buy ? AggressorSide.BUY : AggressorSide.SELL;
    }
}
