package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class Setup91AtrDisplacementTest {
    @Test void closeAtrCannotBeReenabled() {
        assertThatThrownBy(() -> new Setup91(1, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ATR nos fechamentos foi desativado");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void closeReversalStillAcceptsTwentyPointsRegardlessOfLargeCandleRanges(boolean buy) {
        var setup = new Setup91(1, false);
        for (int i = -30; i < 0; i++) feed(setup, i, 200, buy);
        double[] closes = {200, 150, 100, 300, 320};
        Setup91.Observation observation = null;
        for (int i = 0; i < closes.length; i++) observation = feed(setup, i, closes[i], buy);
        assertThat(observation.confirmedReversal()).contains(buy ? Setup91.Candidate.BUY : Setup91.Candidate.SELL);
    }

    private static Setup91.Observation feed(Setup91 setup, int index, double price, boolean buy) {
        double close = 3000 + (buy ? price : 2000 - price);
        long time = 30_000_000L + index * 300_000L;
        return setup.onCandleClosed(new IntrabarCandleSnapshot("WINV26", time, close, close + 500, close - 100, close), time + 300_000);
    }
}
