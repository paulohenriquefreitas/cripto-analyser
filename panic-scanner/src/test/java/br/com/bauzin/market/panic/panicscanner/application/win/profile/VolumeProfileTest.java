package br.com.bauzin.market.panic.panicscanner.application.win.profile;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VolumeProfileTest {
    static final LocalDate DAY = LocalDate.of(2026,10,20);
    static final long START = DAY.atTime(10,0).atZone(VolumeProfile.ZONE).toInstant().toEpochMilli();
    final VolumeProfile.Calendar calendar = new VolumeProfile.Calendar(LocalTime.of(9,0),LocalTime.of(18,0),
            Set.of(),true,Duration.ZERO,true);
    VolumeProfile engine() { return new VolumeProfile("WINZ26", DAY, BigDecimal.valueOf(5),calendar); }
    MarketTrade trade(long time, double price, double volume, AggressorSide side) {
        return new MarketTrade("WINZ26",time,price,volume,side);
    }
    void add(VolumeProfile p, int index, double price, double volume, AggressorSide side) {
        assertTrue(p.accept(trade(START+index,price,volume,side),new VolumeProfile.Identity(START+index,1)));
    }
    void amount(double value, BigDecimal actual) { assertEquals(0,BigDecimal.valueOf(value).compareTo(actual)); }

    @Test void incrementalSidesAndPositiveNegativeZeroDelta() {
        var p=engine();
        add(p,0,100,8,AggressorSide.BUY); add(p,1,100,3,AggressorSide.SELL);
        add(p,2,100,1,AggressorSide.AMBIGUOUS); add(p,3,105,4,AggressorSide.SELL);
        add(p,4,110,2,AggressorSide.BUY); add(p,5,110,2,AggressorSide.SELL);
        var s=p.snapshot(); amount(20,s.totalVolume()); amount(1,s.delta()); assertEquals(6,s.trades());
        var l=s.levels().getFirst(); amount(12,l.totalVolume()); amount(8,l.buyVolume());
        amount(3,l.sellVolume()); amount(1,l.ambiguousVolume()); amount(5,l.delta());
        assertEquals(100,l.buyPercent()+l.sellPercent()+l.ambiguousPercent(),1e-8);
        amount(-4,s.levels().get(1).delta()); amount(0,s.levels().get(2).delta());
        assertSame(s,p.snapshot()); // Normal reads preserve state and reuse the snapshot.
    }
    @Test void identicalTradesWithDistinctOrdinalsAreNotDeduplicatedButDeliveriesAre() {
        var p=engine(); var t=trade(START,100,2,AggressorSide.BUY);
        assertTrue(p.accept(t,new VolumeProfile.Identity(START,1)));
        assertFalse(p.accept(t,new VolumeProfile.Identity(START,1)));
        assertTrue(p.accept(t,new VolumeProfile.Identity(START,2)));
        assertFalse(p.accept(t,new VolumeProfile.Identity(START,1)));
        amount(4,p.snapshot().totalVolume()); assertEquals(2,p.snapshot().trades());
        assertFalse(p.snapshot().integrityCompromised());
    }
    @Test void pocAndValueAreaReachSeventyPercentDeterministically() {
        var p=engine();
        add(p,0,100,40,AggressorSide.BUY); add(p,1,105,30,AggressorSide.SELL);
        add(p,2,110,20,AggressorSide.AMBIGUOUS); add(p,3,115,10,AggressorSide.BUY);
        var s=p.snapshot(); amount(100,s.poc()); amount(100,s.val()); amount(105,s.vah());
        amount(70,s.valueAreaVolume()); assertEquals(70,s.valueAreaPercent());
    }
    @Test void pocTieChoosesLowerAndExpansionTieChoosesLowerSide() {
        var p=engine(); add(p,0,105,10,AggressorSide.BUY); add(p,1,95,10,AggressorSide.BUY);
        amount(95,p.snapshot().poc());
        add(p,2,100,20,AggressorSide.BUY);
        amount(100,p.snapshot().poc()); amount(95,p.snapshot().val()); amount(100,p.snapshot().vah());
    }
    @Test void discontinuousDistributionDoesNotInventZeroVolumeLevelsOrLvn() {
        var p=engine(); add(p,0,100,60,AggressorSide.BUY); add(p,1,120,40,AggressorSide.BUY);
        assertEquals(2,p.snapshot().levels().size()); amount(100,p.snapshot().val()); amount(120,p.snapshot().vah());
        assertTrue(p.snapshot().lvn().isEmpty()); assertNull(p.read(BigDecimal.valueOf(110)).level());
        amount(10,p.read(BigDecimal.valueOf(110)).distanceToPoc());
    }
    @Test void nodeRegionsAndReadInterfaceUseExecutedVolumeOnly() {
        var p=engine(); add(p,0,100,100,AggressorSide.BUY); add(p,1,105,20,AggressorSide.SELL);
        add(p,2,110,70,AggressorSide.AMBIGUOUS);
        assertEquals(2,p.snapshot().hvn().size()); assertEquals(1,p.snapshot().lvn().size());
        var r=p.read(BigDecimal.valueOf(105)); amount(-20,r.level().delta());
        amount(5,r.distanceToHvn()); amount(0,r.distanceToLvn());
    }
    @Test void tickGridIsExactAndFractionalVolumeUsesSameUnit() {
        var p=engine(); add(p,0,100,1.25,AggressorSide.BUY); amount(1.25,p.snapshot().totalVolume());
        assertFalse(p.accept(trade(START+1,100.000001,2,AggressorSide.SELL),new VolumeProfile.Identity(START+1,1)));
        amount(1.25,p.snapshot().totalVolume()); assertTrue(p.snapshot().faults().contains("PRICE_OFF_TICK_GRID"));
    }
    @Test void lateStartIsPartialEvenWithConfirmedCalendarAndOpeningTradeIsNotProof() {
        var p=engine(); add(p,0,100,1,AggressorSide.BUY);
        assertEquals("PARTIAL",p.snapshot().status()); assertTrue(p.snapshot().partial());
        assertTrue(p.snapshot().coverageReasons().contains("FULL_SESSION_HISTORY_UNVERIFIED"));
        assertThrows(IllegalStateException.class,()->p.confirmCoverage(Instant.ofEpochMilli(START),
                DAY.atTime(18,0).atZone(VolumeProfile.ZONE).toInstant()));
    }
    @Test void integrityIsLatchedAndKnownLaterVolumeIsStillExplicitlyIncomplete() {
        var p=engine(); add(p,0,100,1,AggressorSide.BUY); p.fault("SOURCE_GAP");
        add(p,1,105,2,AggressorSide.SELL); amount(3,p.snapshot().totalVolume());
        assertEquals("INCOMPLETE",p.snapshot().status()); assertTrue(p.snapshot().faults().contains("SOURCE_GAP"));
        assertThrows(IllegalStateException.class,()->p.confirmCoverage(Instant.EPOCH,Instant.ofEpochMilli(START+99999999)));
    }
    @Test void anotherSessionCannotEnterExistingProfile() {
        var p=engine(); add(p,0,100,1,AggressorSide.BUY);
        long tomorrow=START+86400000;
        assertFalse(p.accept(trade(tomorrow,105,7,AggressorSide.BUY),new VolumeProfile.Identity(tomorrow,1)));
        amount(1,p.snapshot().totalVolume()); assertTrue(p.snapshot().faults().contains("SESSION_MISMATCH"));
    }
    @Test void ordinalGapAndChangedIdentityAreIntegrityFaults() {
        var p=engine(); add(p,0,100,1,AggressorSide.BUY);
        assertTrue(p.accept(trade(START,105,2,AggressorSide.SELL),new VolumeProfile.Identity(START,3)));
        assertTrue(p.snapshot().faults().contains("SOURCE_ORDINAL_GAP"));
        assertFalse(p.accept(trade(START,110,2,AggressorSide.SELL),new VolumeProfile.Identity(START,3)));
        assertTrue(p.snapshot().faults().contains("SOURCE_IDENTITY_CHANGED"));
    }
    @Test void calendarHolidaysAndSaoPauloTimezoneAreRespected() {
        var closed=new VolumeProfile.Calendar(LocalTime.of(9,0),LocalTime.of(18,0),Set.of(DAY),true,Duration.ZERO,true);
        var p=new VolumeProfile("WINZ26",DAY,BigDecimal.valueOf(5),closed);
        assertFalse(p.accept(trade(START,100,2,AggressorSide.BUY),new VolumeProfile.Identity(START,1)));
        assertTrue(p.snapshot().faults().contains("OUTSIDE_CONFIGURED_SESSION"));
        assertEquals(DAY.minusDays(1),calendar.local(DAY.atTime(1,0).toInstant(ZoneOffset.UTC).toEpochMilli()).toLocalDate());
    }
    @Test void auditedFullCoverageIsExplicitAndNeverInferredFromFirstTrade() {
        var p=engine(); add(p,0,100,1,AggressorSide.BUY);
        p.confirmCoverage(DAY.atTime(9,0).atZone(VolumeProfile.ZONE).toInstant(),
                          DAY.atTime(18,0).atZone(VolumeProfile.ZONE).toInstant());
        assertEquals("COMPLETE",p.snapshot().status());
        assertEquals("COMPLETE",p.read(BigDecimal.valueOf(100)).status());
        add(p,1,105,1,AggressorSide.BUY); assertEquals("PARTIAL",p.snapshot().status());
        p.fault("DISCONNECTED"); assertEquals("INCOMPLETE",p.snapshot().status());
    }
    @Test void knownReplayMatchesAtEveryPrefix() {
        var a=engine(); var b=engine();
        for(int i=0;i<100;i++) {
            var t=trade(START+i/2,100+(i%4)*5,1+(i%3),AggressorSide.values()[i%3]);
            var id=new VolumeProfile.Identity(t.timeMsc(),i%2+1);
            assertTrue(a.accept(t,id)); assertTrue(b.accept(t,id));
            assertEquals(a.snapshot(),b.snapshot());
            assertFalse(b.accept(t,id)); assertEquals(a.snapshot(),b.snapshot());
        }
        assertEquals(100,a.snapshot().trades()); amount(199,a.snapshot().totalVolume());
    }
}
