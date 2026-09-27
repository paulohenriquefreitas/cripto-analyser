package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5CanonicalPriceMapper;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CausalSessionVwapTest {
    private static final long DAY = Instant.parse("2026-09-21T00:00:00Z").toEpochMilli();

    @Test
    void accumulatesOnlyEligibleVolumeAndUsesOnlyEventsSeenSoFar() {

        CausalSessionVwap vwap = new CausalSessionVwap();
        vwap.warmUp("WINV26", List.of(
                new Mt5Candle((DAY - 300_000) / 1000, 90, 90, 90, 90, 0, 10)));

        assertThat(vwap.update(DAY + 1, 100, 5)).isEqualTo(100);
        assertThat(vwap.currentVolume()).isEqualTo(5);

        assertThat(vwap.update(DAY + 2, 110, 0)).isCloseTo(106.6666666667,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(vwap.currentVolume()).isEqualTo(5);

        assertThat(vwap.update(DAY + 3, 110, 5)).isCloseTo(106.6666666667,
                org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(vwap.currentVolume()).isEqualTo(10);
    }

    @Test
    void startsASeparateUtcSessionWithoutCarryingPreviousDayVwap() {
        CausalSessionVwap vwap = new CausalSessionVwap();
        vwap.warmUp("WINV26", List.of(
                new Mt5Candle((DAY - 300_000) / 1000, 90, 90, 90, 90, 0, 100)));

        assertThat(vwap.update(DAY + 86_400_000 + 1, 200, 10)).isEqualTo(200);
    }

    @Test
    void acceptsRealMt5FlagsAndCountsRepeatedSameTimestampEventsOnceEach() {
        CausalSessionVwap vwap = new CausalSessionVwap();
        vwap.warmUp("WINV26", List.of(
                new Mt5Candle((DAY - 300_000) / 1000, 90, 90, 90, 90, 0, 10)));

        var buy = new HistoricalCanonicalPriceEvent(
                new CanonicalPriceEvent("WINV26", DAY + 1, 100), 5, 1336, 0);
        var sell = new HistoricalCanonicalPriceEvent(
                new CanonicalPriceEvent("WINV26", DAY + 1, 110), 7, 1368, 1);

        assertThat(CausalSessionVwap.isEligibleVolume(buy)).isTrue();
        assertThat(CausalSessionVwap.isEligibleVolume(sell)).isTrue();
        assertThat((1336 & Mt5CanonicalPriceMapper.TICK_FLAG_LAST) != 0).isTrue();
        assertThat((1336 & Mt5CanonicalPriceMapper.TICK_FLAG_VOLUME) != 0).isTrue();
        assertThat((1368 & Mt5CanonicalPriceMapper.TICK_FLAG_LAST) != 0).isTrue();
        assertThat((1368 & Mt5CanonicalPriceMapper.TICK_FLAG_VOLUME) != 0).isTrue();

        assertThat(vwap.update(buy.priceEvent().timeMsc(), buy.priceEvent().price(), buy.volumeReal()))
                .isEqualTo(100);
        assertThat(vwap.update(sell.priceEvent().timeMsc(), sell.priceEvent().price(), sell.volumeReal()))
                .isCloseTo(106.6666666667, org.assertj.core.data.Offset.offset(0.0000001));
        assertThat(vwap.currentVolume()).isEqualTo(12);
        assertThat(buy.priceEvent().timeMsc()).isEqualTo(sell.priceEvent().timeMsc());
        assertThat(buy.sequence()).isNotEqualTo(sell.sequence());
    }
}
