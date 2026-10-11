package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class Pullback09DownSetupTest {
    private static final long C1 = 6_000_000, C2 = C1 + 300_000, C3 = C2 + 300_000, C4 = C3 + 300_000;

    @Test
    void validRedCandleConfirmsOnlyAtC4AndStaysAssociatedWithC3() {
        Feed f = rejection();
        assertThat(f.tick(C3, 51, 100)).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_DOWN);
        assertThat(f.tick(C3 + 1, 49, 100)).isEmpty();
        assertThat(f.tick(C3 + 2, 48, 100)).isEmpty();
        var events = f.tick(C4 + 7, 80, 100);
        assertThat(events).hasSize(1);
        var e = events.getFirst();
        assertThat(e.eventType()).isEqualTo(Pullback09Setup.EventType.PULLB09_DOWN);
        assertThat(e.timeMsc()).isEqualTo(C4 + 7);
        assertThat(e.candleTimeMsc()).isEqualTo(C3);
        assertThat(e.price()).isEqualTo(48);
        assertThat(f.tick(C4 + 8, 40, 100)).isEmpty();
        assertThat(f.tick(C4 + 300_000, 30, 100)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "B wick only,60,40,55",
            "C green despite close below low2,40,30,45",
            "D strict equality,60,40,50",
            "E no break,60,55,56",
            "neutral below low2,40,30,40"
    })
    void invalidC3CannotConfirmIntrabarOrLaterInC4(String name, double open, double low, double close) {
        Feed f = rejection();
        assertThat(f.tick(C3, open, 100)).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_DOWN);
        assertThat(f.tick(C3 + 1, low, 100)).isEmpty();
        assertThat(f.tick(C3 + 2, close, 100)).isEmpty();
        assertThat(f.tick(C4, 20, 100)).isEmpty();
        assertThat(f.tick(C4 + 1, 10, 100)).isEmpty();
        assertThat(f.tick(C4 + 300_000, 5, 100)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"31,false", "30,true", "29,true", "0,true", "-1,true"})
    void directionalRetestZoneMirrorsUp(double distance, boolean expected) {
        Feed f = cross();
        f.tick(C2 + 1, 100 - distance, 100);
        f.tick(C2 + 2, 60, 100);
        var events = f.tick(C3, 51, 100);
        assertThat(events).hasSize(expected ? 1 : 0);
        if (expected) assertThat(events.getFirst().eventType()).isEqualTo(Pullback09Setup.EventType.RJ09_DOWN);
    }

    @Test
    void finalPositionAloneDoesNotCreateCrzAndGreenCrossDoesNotQualify() {
        Feed f = new Feed(); f.tick(C1, 95, 100); f.tick(C1 + 1, 110, 100); f.tick(C1 + 2, 90, 100);
        assertThat(f.tick(C2, 90, 100)).isEmpty();
        Feed green = new Feed(); green.tick(C1, 90, 80); green.tick(C1 + 1, 95, 100);
        assertThat(green.tick(C2, 95, 100)).isEmpty();
    }

    @Test
    void retestUsesSmaAtEachLastRatherThanFinalAverage() {
        Feed f = cross(); f.tick(C2 + 1, 69, 100); f.tick(C2 + 2, 60, 91);
        assertThat(f.tick(C3, 49, 91)).isEmpty();
    }

    @Test
    void onlyImmediateC2AndC3AreEligible() {
        Feed f = cross(); f.tick(C3, 50, 100); f.tick(C3 + 1, 100, 100); f.tick(C3 + 2, 60, 100);
        assertThat(f.tick(C4, 49, 100)).isEmpty();
        Feed missing = rejection();
        assertThat(missing.tick(C4, 51, 100)).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.RJ09_DOWN);
        assertThat(missing.tick(C4 + 1, 40, 100)).isEmpty();
        assertThat(missing.tick(C4 + 300_000, 30, 100)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"149,160,151", "149,160,140", "170,180,160", "140,160,150", "140,149,148"})
    void downEventsExactlyMirrorUpForReflectedPrices(double open, double high, double close) {
        var up = new Pullback09Setup(); var down = new Pullback09DownSetup();
        long[] times = {C1, C1 + 1, C2, C2 + 1, C2 + 2, C3, C3 + 1, C3 + 2, C4, C4 + 1, C4 + 300_000};
        double[] prices = {90, 150, 150, 100, 140, open, high, close, 120, 130, 125};
        IntrabarCandleSnapshot previous = null;
        for (int i = 0; i < times.length; i++) {
            long bucket = M5Bucket.start(times[i]); double price = prices[i];
            var candle = previous == null || previous.bucketStartTimeMsc() != bucket
                    ? new IntrabarCandleSnapshot("WINV26", bucket, price, price, price, price)
                    : new IntrabarCandleSnapshot("WINV26", bucket, previous.open(), Math.max(previous.high(), price), Math.min(previous.low(), price), price);
            var mirrored = new IntrabarCandleSnapshot("WINV26", bucket, 500-candle.open(), 500-candle.low(), 500-candle.high(), 500-candle.close());
            var ue = up.onEvent(new IntrabarMarketState("WINV26", times[i], price, candle, 100.0, 100.0, null));
            var de = down.onEvent(new IntrabarMarketState("WINV26", times[i], 500-price, mirrored, 400.0, 400.0, null));
            assertThat(de).hasSameSizeAs(ue);
            for (int j = 0; j < ue.size(); j++) {
                assertThat(de.get(j).eventType().name()).isEqualTo(ue.get(j).eventType().name().replace("_UP", "_DOWN"));
                assertThat(de.get(j).eventId()).isEqualTo(ue.get(j).eventId().replace("_UP", "_DOWN"));
                assertThat(de.get(j).timeMsc()).isEqualTo(ue.get(j).timeMsc());
                assertThat(de.get(j).candleTimeMsc()).isEqualTo(ue.get(j).candleTimeMsc());
                assertThat(de.get(j).price()).isEqualTo(500-ue.get(j).price());
            }
            previous = candle;
        }
    }

    private static Feed cross() {
        Feed f = new Feed(); f.tick(C1, 110, 100); f.tick(C1 + 1, 50, 100);
        assertThat(f.tick(C2, 50, 100)).extracting(Pullback09Setup.Event::eventType)
                .containsExactly(Pullback09Setup.EventType.CRZ09_DOWN);
        return f;
    }
    private static Feed rejection() {
        Feed f = cross(); f.tick(C2 + 1, 100, 100); f.tick(C2 + 2, 60, 100); return f;
    }
    private static class Feed {
        final Pullback09DownSetup setup = new Pullback09DownSetup();
        IntrabarCandleSnapshot candle;
        List<Pullback09Setup.Event> tick(long time, double price, double sma) {
            long bucket = M5Bucket.start(time);
            candle = candle == null || candle.bucketStartTimeMsc() != bucket
                    ? new IntrabarCandleSnapshot("WINV26", bucket, price, price, price, price)
                    : new IntrabarCandleSnapshot("WINV26", bucket, candle.open(), Math.max(candle.high(), price), Math.min(candle.low(), price), price);
            return setup.onEvent(new IntrabarMarketState("WINV26", time, price, candle, sma, 100.0, null));
        }
    }
}
