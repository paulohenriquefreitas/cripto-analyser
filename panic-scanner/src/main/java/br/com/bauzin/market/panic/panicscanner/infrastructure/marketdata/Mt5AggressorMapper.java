package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;

/** BUY/SELL are source flags, never inferred from a price movement. */
public final class Mt5AggressorMapper {
    private Mt5AggressorMapper() {}
    public static AggressorSide side(int flags) {
        int extra = flags & ~126;
        if (flags < 0 || (extra != 0 && extra != 1024 && extra != 1280) || (flags & 24) == 0)
            return AggressorSide.AMBIGUOUS;
        boolean buy = (flags & 32) != 0;
        boolean sell = (flags & 64) != 0;
        return buy == sell ? AggressorSide.AMBIGUOUS : buy ? AggressorSide.BUY : AggressorSide.SELL;
    }
}
