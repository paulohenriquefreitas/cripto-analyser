package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit local-data regression; normal CI does not require proprietary tick history. */
@EnabledIfSystemProperty(named = "setup91.replay.history", matches = ".+")
class Setup91September9ReplayTest {
    @Test
    void september9ArmsElevenOClockSignalAndTriggersInNextBucket() throws Exception {
        long signal = Instant.parse("2026-09-09T11:00:00Z").toEpochMilli();
        long execution = signal + 300_000;
        var session = new M5ReplaySession(Path.of(System.getProperty("setup91.replay.history")), "WINV26", Long.MAX_VALUE);
        var setups = new CopyOnWriteArrayList<M5ReplaySession.SetupEventMessage>();
        var trades = new CopyOnWriteArrayList<Setup91TriggerResearch.TradeEvent>();
        var reversals = new CopyOnWriteArrayList<M5ReplaySession.ClosedEma9>();
        var reached = new CountDownLatch(1);
        session.addSetupEventListener(e -> { if (e.candleTimeMsc() == signal) setups.add(e); });
        session.addTradeListener(e -> { if (e.timeMsc() >= execution && e.timeMsc() < execution + 300_000) trades.add(e); });
        session.addListener(s -> {
            if (s.closedEma9() != null && s.closedEma9().reversal() != null) reversals.addIfAbsent(s.closedEma9());
            if (s.type().equals("MARKET_STATE") && s.timeMsc() >= execution + 300_000) {
                session.pause(); reached.countDown();
            }
            if (s.type().equals("ERROR") || s.type().equals("COMPLETED")) reached.countDown();
        });
        try {
            session.play();
            assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.PAUSED);
            assertThat(setups).extracting(M5ReplaySession.SetupEventMessage::setupType)
                    .containsExactly("SETUP91_SELL_ARMED", "SETUP91_SELL_TRIGGERED");
            assertThat(setups).allSatisfy(e -> {
                assertThat(e.timeMsc()).isGreaterThanOrEqualTo(execution).isLessThan(execution + 300_000);
                assertThat(e.signal().candle().close()).isEqualTo(188680);
                assertThat(e.signal().previousSlope()).isPositive();
                assertThat(e.signal().currentSlope()).isNegative();
            });
            assertThat(setups.getLast().price()).isLessThan(188605);
            assertThat(reversals).anySatisfy(r -> {
                assertThat(r.time()).isEqualTo(execution / 1000);
                assertThat(r.reversal()).isEqualTo("DOWN");
                assertThat(r.previousValue()).isCloseTo(188740.6294445808, org.assertj.core.data.Offset.offset(1e-8));
                assertThat(r.value()).isCloseTo(188702.5035556646, org.assertj.core.data.Offset.offset(1e-8));
                assertThat(r.availableAtTimeMsc()).isGreaterThanOrEqualTo(execution + 300_000);
            });
            assertThat(reversals).noneMatch(r -> r.time() == signal / 1000);
            reversals.stream().filter(r -> r.time() == execution / 1000).distinct()
                    .forEach(r -> System.out.println("SEPTEMBER9 EMA9 REVERSAL " + r));
            assertThat(trades).hasSize(1);
            assertThat(trades.getFirst().stage()).isEqualTo(Setup91TriggerResearch.Stage.ENTRY);
            assertThat(trades.getFirst().risk()).isEqualTo(455);
            assertThat(trades.getFirst().reason()).isNull();
            setups.forEach(e -> System.out.println("SEPTEMBER9 " + Instant.ofEpochMilli(e.timeMsc()) + " " + e));
            trades.forEach(e -> System.out.println("SEPTEMBER9 TRADE " + e));
        } finally { session.close(); }
    }
}
