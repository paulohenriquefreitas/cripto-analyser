package br.com.bauzin.market.panic.panicscanner.application.win.profile;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5AggressorMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional local evidence: no capture is copied into source control or reconstructed. */
class VolumeProfileRealCaptureTest {
    @Test void recordedThirtySecondCaptureHasKnownExecutedVolumeAndPoc() throws Exception {
        Path file=Path.of("dom-captures/WINZ26-joint-probe-30s.ndjson");
        if (!Files.exists(file)) file=Path.of("panic-scanner").resolve(file);
        assumeTrue(Files.exists(file),"Local real capture unavailable; synthetic tests still run");
        var mapper=new ObjectMapper(); VolumeProfile profile=null;
        long last=-1, ordinal=0;
        try(var lines=Files.lines(file)) {
            for(String line:(Iterable<String>)lines::iterator) {
                var r=mapper.readTree(line);
                if (!"tick_batch".equals(r.path("kind").asText())) continue;
                for(var t:r.path("ticks")) {
                    if (!"trade".equals(t.path("classification").asText())) continue;
                    var raw=t.path("raw"); long time=raw.path("time_msc").asLong();
                    if (profile==null) profile=new VolumeProfile("WINZ26",
                        Instant.ofEpochMilli(time).atZone(VolumeProfile.ZONE).toLocalDate(),BigDecimal.valueOf(5),
                        new VolumeProfile.Calendar(LocalTime.of(9,0),LocalTime.of(18,0),Set.of(),false,Duration.ZERO,false));
                    ordinal=time==last ? ordinal+1 : 1; last=time;
                    assertTrue(profile.accept(new MarketTrade("WINZ26",time,raw.path("last").asDouble(),
                        raw.path("volume_real").asDouble(),Mt5AggressorMapper.side(raw.path("flags").asInt())),
                        new VolumeProfile.Identity(time,ordinal)));
                }
            }
        }
        assertNotNull(profile);
        var s=profile.snapshot(); assertEquals(566,s.trades()); assertEquals(4685,s.totalVolume().doubleValue());
        assertEquals(208350,s.poc().doubleValue()); assertEquals(12,s.levels().size());
        assertEquals(1444,s.levels().stream().map(VolumeProfile.Level::buyVolume).reduce(BigDecimal.ZERO,BigDecimal::add).doubleValue());
        assertEquals(3188,s.levels().stream().map(VolumeProfile.Level::sellVolume).reduce(BigDecimal.ZERO,BigDecimal::add).doubleValue());
        assertEquals(53,s.levels().stream().map(VolumeProfile.Level::ambiguousVolume).reduce(BigDecimal.ZERO,BigDecimal::add).doubleValue());
        assertEquals(-1744,s.delta().doubleValue()); assertEquals("PARTIAL",s.status());
    }
}
