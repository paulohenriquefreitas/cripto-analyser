package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "setup91.replay.september14", matches = ".+")
class Setup91September14ReplayTest {
    @Test
    void consolidationFrom1215DoesNotProduceReversalArrows() throws Exception {
        long begin = Instant.parse("2026-09-14T12:15:00Z").getEpochSecond();
        long end = Instant.parse("2026-09-14T13:00:00Z").getEpochSecond();
        var session = new M5ReplaySession(Path.of(System.getProperty("setup91.replay.september14")), "WINV26", Long.MAX_VALUE);
        var observations = new ConcurrentHashMap<Long, M5ReplaySession.ClosedEma9>();
        var reached = new CountDownLatch(1);
        session.addListener(s -> {
            if (s.closedEma9() != null && s.closedEma9().time() >= begin && s.closedEma9().time() <= end) {
                observations.put(s.closedEma9().time(), s.closedEma9());
            }
            if (s.type().equals("MARKET_STATE") && s.timeMsc() >= (end + 300) * 1000) {
                session.pause(); reached.countDown();
            }
            if (s.type().equals("ERROR") || s.type().equals("COMPLETED")) reached.countDown();
        });
        try {
            session.play();
            assertThat(reached.await(60, TimeUnit.SECONDS)).isTrue();
            assertThat(session.status()).isEqualTo(M5ReplaySession.Status.PAUSED);
            assertThat(observations.values()).isNotEmpty().allSatisfy(o -> assertThat(o.reversal()).isNull());
            assertThat(observations.keySet())
                    .contains(Instant.parse("2026-09-14T12:45:00Z").getEpochSecond(),
                            Instant.parse("2026-09-14T12:50:00Z").getEpochSecond());
        } finally { session.close(); }
    }
}
