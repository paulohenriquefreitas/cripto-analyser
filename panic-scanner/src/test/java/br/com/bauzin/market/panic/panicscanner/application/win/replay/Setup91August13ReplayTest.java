package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "setup91.replay.august13", matches = ".+")
class Setup91August13ReplayTest {
    @Test
    void gradualTurnConfirmsAt1330WithoutEarlyOrRepeatedArrows() throws Exception {
        long begin = Instant.parse("2026-08-13T13:00:00Z").getEpochSecond();
        long signal = Instant.parse("2026-08-13T13:30:00Z").getEpochSecond();
        long stop = Instant.parse("2026-08-13T13:45:00Z").toEpochMilli();
        var session = new M5ReplaySession(Path.of(System.getProperty("setup91.replay.august13")), "WINV26", Long.MAX_VALUE);
        var observations = new ConcurrentHashMap<Long, M5ReplaySession.ClosedEma9>();
        var reached = new CountDownLatch(1);
        session.addListener(s -> {
            if (s.closedEma9() != null && s.closedEma9().time() >= begin) {
                observations.put(s.closedEma9().time(), s.closedEma9());
            }
            if (s.type().equals("MARKET_STATE") && s.timeMsc() >= stop) {
                session.pause(); reached.countDown();
            }
            if (s.type().equals("ERROR") || s.type().equals("COMPLETED")) reached.countDown();
        });
        try {
            session.play();
            assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.PAUSED);
            assertThat(observations.values().stream().filter(o -> o.reversal() != null))
                    .singleElement().satisfies(o -> {
                        assertThat(o.time()).isEqualTo(signal);
                        assertThat(o.reversal()).isEqualTo("UP");
                        assertThat(o.availableAtTimeMsc()).isEqualTo((signal + 300) * 1000 + 55);
                    });
            assertThat(observations.keySet()).contains(begin, signal + 600);
        } finally { session.close(); }
    }
}
