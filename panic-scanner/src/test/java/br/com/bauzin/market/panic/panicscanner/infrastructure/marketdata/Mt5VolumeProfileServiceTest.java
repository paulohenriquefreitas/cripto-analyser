package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.profile.VolumeProfile;
import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class Mt5VolumeProfileServiceTest {
    Mt5VolumeProfileService service() { return new Mt5VolumeProfileService("09:00","18:00","",true,0,true); }
    void add(Mt5VolumeProfileService s, int day, double volume) {
        long time=LocalDate.of(2026,10,day).atTime(10,0).atZone(VolumeProfile.ZONE).toInstant().toEpochMilli();
        s.onTrade(new MarketTrade("WINZ26",time,100,volume,AggressorSide.BUY),new Mt5TradeStreamClient.Cursor(time,1));
    }
    @Test void rolloverSeparatesSessionsAndDoesNotRecoverLatchedSourceFault() {
        var s=service(); s.connectionStatus(true,0); add(s,20,2); s.disconnected(); s.connectionStatus(true,0);
        add(s,21,3); var view=s.snapshot();
        assertEquals(3,view.profile().totalVolume().doubleValue()); assertEquals(2,view.previousSession().totalVolume().doubleValue());
        assertEquals("2026-10-21",view.profile().session()); assertEquals("INCOMPLETE",view.profile().status());
        assertTrue(view.profile().faults().contains("SOURCE_DISCONNECTED"));
    }
    @Test void sourceIdentityDeduplicatesAndRepeatedPayloadWithNewIdentitySurvives() {
        var s=service(); add(s,20,2); add(s,20,2);
        assertEquals(1,s.snapshot().profile().trades());
        assertTrue(s.snapshot().profile().partial());
    }
    @Test void invalidVolumeNeverAppearsAsZeroOrDisappearsOnReconnect() {
        var s=service(); add(s,20,2); s.invalidVolume(0); s.connectionStatus(true,0); add(s,21,3);
        assertTrue(s.snapshot().profile().faults().contains("INVALID_VOLUME"));
        assertTrue(s.snapshot().profile().integrityCompromised());
    }
    @Test void noTradesIsAbsentNotAnEmptyCompleteSession() {
        var s=service(); assertNull(s.snapshot().profile()); s.disconnected();
        assertTrue(s.snapshot().sourceFaults().contains("SOURCE_DISCONNECTED"));
    }
    @Test void engineIntegrityFailureAlsoSurvivesSessionRollover() {
        var s=service(); add(s,20,2);
        long time=LocalDate.of(2026,10,20).atTime(10,0).atZone(VolumeProfile.ZONE).toInstant().toEpochMilli();
        s.onTrade(new MarketTrade("WINZ26",time,105,1,AggressorSide.BUY),new Mt5TradeStreamClient.Cursor(time,3));
        add(s,21,2);
        assertTrue(s.snapshot().profile().faults().contains("SOURCE_ORDINAL_GAP"));
    }
}
