package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

class Mt5TimestampDiagnosticsTest {
    @Test
    void preservesRawTimestampAndConvertsEpochSeconds() throws Exception {
        var candle = Mt5ProcessClient.parseResponse(0, """
                [{"time":1789989000,"open":10,"high":10,"low":10,"close":10,
                  "tickVolume":0,"realVolume":0}]
                """, "").getFirst();
        assertEquals(1789989000L, candle.time());
        assertEquals(Instant.parse("2026-09-21T11:10:00Z"),
                Mt5TimestampDiagnostics.toInstant(candle.time()));
        assertEquals(Instant.EPOCH, Mt5TimestampDiagnostics.toInstant(0));
    }

    @Test
    void convertsInstantToExplicitSaoPauloZone() {
        var local = Mt5TimestampDiagnostics.inSaoPaulo(Instant.parse("2026-09-21T11:10:00Z"));
        assertEquals(LocalDateTime.of(2026, 9, 21, 8, 10), local.toLocalDateTime());
        assertEquals(ZoneId.of("America/Sao_Paulo"), local.getZone());
        assertEquals(ZoneOffset.ofHours(-3), local.getOffset());
        assertEquals(Instant.parse("2026-09-21T11:10:00Z"), local.toInstant());
    }

    @Test
    void usesZoneRulesRatherThanAFixedOffset() {
        var local = Mt5TimestampDiagnostics.inSaoPaulo(Instant.parse("2018-01-15T12:00:00Z"));
        assertEquals(LocalDateTime.of(2018, 1, 15, 10, 0), local.toLocalDateTime());
        assertEquals(ZoneOffset.ofHours(-2), local.getOffset());
    }

    @Test
    @ResourceLock("java.util.TimeZone.default")
    void presentationDoesNotDependOnDefaultJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            String expected = """
                    Raw timestamp: 1789989000
                    Instant: 2026-09-21T11:10:00Z
                    UTC: 21/09/2026 11:10:00.000 Z
                    America/Sao_Paulo: 21/09/2026 08:10:00.000 -03:00""";
            for (String zone : new String[] {"UTC", "Asia/Tokyo", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertEquals(expected, Mt5TimestampDiagnostics.describeTime(1789989000L));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void preservesTickMilliseconds() {
        assertEquals(Instant.parse("2026-09-21T11:28:40.534Z"),
                Instant.ofEpochMilli(1789990120534L));
        assertEquals(Instant.parse("2026-09-21T11:28:40Z"),
                Mt5TimestampDiagnostics.toInstant(1789990120L));
    }
}
