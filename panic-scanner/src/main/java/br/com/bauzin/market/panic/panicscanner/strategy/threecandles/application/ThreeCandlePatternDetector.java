package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;

/** Future detector port. No recognition rules or trading signals in this phase. */
@FunctionalInterface
public interface ThreeCandlePatternDetector {
    void onCandle(CandleUpdate update);
}
