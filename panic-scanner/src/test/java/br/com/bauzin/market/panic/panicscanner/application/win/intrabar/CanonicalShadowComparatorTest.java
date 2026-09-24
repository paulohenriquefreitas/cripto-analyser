package br.com.bauzin.market.panic.panicscanner.application.win.intrabar;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.application.win.intrabar.CanonicalShadowComparator.Difference.*;

class CanonicalShadowComparatorTest {
    static final long START = 6_000_000;
    static IntrabarMarketState state(long t, double o, double h, double l, double c, Double s9, Double s21) {
        return new IntrabarMarketState("WINV26", t, c,
                new IntrabarCandleSnapshot("WINV26", M5Bucket.start(t), o, h, l, c), s9, s21, null);
    }
    static IntrabarMarketState standard(long t) { return state(t, 100, 110, 90, 101, 100., 100.); }
    static CanonicalPriceEvent event(IntrabarMarketState s) { return new CanonicalPriceEvent(s.symbol(), s.timeMsc(), s.price()); }
    static CanonicalShadowComparator ready() { var c = new CanonicalShadowComparator(); c.initialize(START); return c; }
    static long count(CanonicalShadowComparator c, String section, CanonicalShadowComparator.Difference d) {
        return ((Map<?, ?>) c.report().get(section)).get(d) == null ? 0 : (Long) ((Map<?, ?>) c.report().get(section)).get(d);
    }
    @Test void identicalStates() { assertTrue(CanonicalShadowComparator.compare(CanonicalShadowComparator.asOld(standard(START)), standard(START)).isEmpty()); }

    @ParameterizedTest @EnumSource(value=CanonicalShadowComparator.Difference.class,
            names={"OPEN_MISMATCH","HIGH_MISMATCH","LOW_MISMATCH","CLOSE_MISMATCH","SMA9_MISMATCH","SMA21_MISMATCH","BUCKET_MISMATCH","PRICE_MISMATCH"})
    void distinguishesFields(CanonicalShadowComparator.Difference field) {
        var baseline = standard(START);
        var b = baseline.candle();
        var old = new ShadowObservation("WINV26", START, field == PRICE_MISMATCH ? 102 : 101,
                new IntrabarCandleSnapshot("WINV26", field == BUCKET_MISMATCH ? START + 300_000 : START,
                        field == OPEN_MISMATCH ? 99 : b.open(), field == HIGH_MISMATCH ? 111 : b.high(),
                        field == LOW_MISMATCH ? 89 : b.low(), field == CLOSE_MISMATCH ? 102 : b.close()),
                field == SMA9_MISMATCH ? 101. : 100., field == SMA21_MISMATCH ? 101. : 100., true, 0);
        assertEquals(Set.of(field), CanonicalShadowComparator.compare(old, baseline));
    }
    @Test void floatingToleranceIsSmallAndAbsolute() {
        var old = CanonicalShadowComparator.asOld(standard(START));
        assertTrue(CanonicalShadowComparator.compare(old, state(START,100,110,90,101,100.+5e-9,100.-5e-9)).isEmpty());
        assertEquals(Set.of(SMA9_MISMATCH,SMA21_MISMATCH), CanonicalShadowComparator.compare(old, state(START,100,110,90,101,100.+2e-8,100.-2e-8)));
    }
    @Test void alignsByEventTimeAfterWatermarkNotArrivalOrder() {
        var c = ready(); var first = standard(START); var future = state(START+100,100,120,90,120,102.,102.);
        c.canonical(event(first),first); c.canonical(event(future),future);
        c.old(CanonicalShadowComparator.asOld(first)); assertEquals(0,c.eventComparisons());
        c.watermark(START+200); assertEquals(1,c.eventComparisons());
        assertEquals(0,count(c,"oldVsCanonicalIntrabar",PRICE_MISMATCH));
    }
    @Test void sameMillisecondIsPreservedButAmbiguousJoinIsSkipped() {
        var c = ready(); var s = standard(START);
        c.canonical(event(s),s); c.canonical(event(s),s); c.old(CanonicalShadowComparator.asOld(s)); c.watermark(START);
        assertEquals(0,c.eventComparisons()); assertEquals(1L,c.report().get("ambiguousSameMillisecond"));
        assertEquals(2L,c.report().get("canonicalEventsIncludingBootstrap"));
    }
    @Test void warmupIncompleteIsNotComparable() {
        var c = ready(); var s = state(START,100,110,90,101,null,null);
        c.canonical(event(s),s); c.old(CanonicalShadowComparator.asOld(s)); c.watermark(START);
        assertEquals(CanonicalShadowComparator.Readiness.SHADOW_NOT_READY,c.readiness()); assertEquals(0,c.eventComparisons());
    }
    @Test void beforeAnchorAndSyncReplayAreNotCompared() {
        var c = ready(); var s = standard(START-1); c.canonical(event(s),s); c.old(CanonicalShadowComparator.asOld(s));
        var next = standard(START); c.canonical(event(next),next);
        c.old(new ShadowObservation("WINV26",START,101,next.candle(),100.,100.,false,1)); c.watermark(START);
        assertEquals(0,c.eventComparisons());
    }
    @Test void missingCanonicalIsReported() {
        var c=ready(); c.old(CanonicalShadowComparator.asOld(standard(START))); c.watermark(START);
        assertEquals(1,count(c,"oldVsCanonicalIntrabar",MISSING_CANONICAL_STATE));
    }
    @Test void orderAndLateEventsFailExplicitly() {
        var c=ready(); var s=standard(START+1); c.canonical(event(s),s);
        assertThrows(IllegalStateException.class,()->c.canonical(event(standard(START)),standard(START)));
        c.watermark(START+1);
        assertThrows(IllegalStateException.class,()->c.canonical(event(s),s));
    }
    @Test void increasingEventAtReleasedWatermarkIsSourceGapNotOrderError() {
        var c = ready();
        var first = standard(START + 2000);
        var late = standard(START + 2999);
        c.canonical(event(first), first);
        c.watermark(START + 2999);
        var ex = assertThrows(IllegalStateException.class, () -> c.canonical(event(late), late));
        assertEquals("SOURCE_GAP late timeMsc=6002999 watermark=6002999", ex.getMessage());
        assertEquals(1, count(c, "oldVsCanonicalIntrabar", SOURCE_GAP));
        assertEquals(0, count(c, "oldVsCanonicalIntrabar", ORDER_ERROR));
        assertEquals(1L, c.report().get("canonicalEventsIncludingBootstrap"));

        var safe = ready();
        var initial = standard(START);
        safe.canonical(event(initial), initial);
        safe.watermark(START + 1999);
        safe.canonical(event(first), first);
        safe.canonical(event(late), late);
        safe.canonical(event(late), late); // legitimate identical LAST preserved
        safe.watermark(START + 2999);
        assertEquals(0, count(safe, "oldVsCanonicalIntrabar", SOURCE_GAP));
        assertEquals(4L, safe.report().get("canonicalEventsIncludingBootstrap"));
    }
    static List<IntrabarCandleSnapshot> warmup() {
        var bars=new ArrayList<IntrabarCandleSnapshot>();
        for(int i=0;i<20;i++) bars.add(new IntrabarCandleSnapshot("WINV26",i*300_000L,188700,188700,188700,188700));
        return bars;
    }
    static IntrabarM5Processor processor() { var p=new IntrabarM5Processor(); p.warmUp("WINV26",START,warmup()); return p; }
    static Ta4jMt5Sma9Adapter legacy() {
        var a=new Ta4jMt5Sma9Adapter(); var bars=new ArrayList<Mt5Candle>();
        for(var b:warmup()) bars.add(new Mt5Candle(b.bucketStartTimeMsc()/1000,b.open(),b.high(),b.low(),b.close(),0,0));
        bars.add(new Mt5Candle(START/1000,188700,188700,188700,188700,0,0)); a.synchronize(bars); return a;
    }
    static ShadowObservation old(Ta4jMt5Sma9Adapter a,long t,double price) {
        var value=a.onTick(new Mt5Tick(t/1000,t,price,price,price));
        return new ShadowObservation("WINV26",t,price,a.candleSnapshot("WINV26"),value.value(),value.sma21(),true,0);
    }
    @Test void actualLegacyAdapterAndCanonicalMatchOnSameSequence() {
        var p=processor();var a=legacy();
        double[] prices={188700,188705,188750,188710};
        for(int i=0;i<prices.length;i++) {
            var s=p.onEvent(new CanonicalPriceEvent("WINV26",START+i,prices[i]));
            assertTrue(CanonicalShadowComparator.compare(old(a,START+i,prices[i]),s).isEmpty());
        }
    }
    @Test void pollingMissesExtremeAndOfficialConfirmsCanonical() {
        var c=ready();var p=processor();var a=legacy(); IntrabarMarketState last=null;
        double[] prices={188700,188705,188750,188710};
        for(int i=0;i<prices.length;i++) {
            var e=new CanonicalPriceEvent("WINV26",START+i,prices[i]); last=p.onEvent(e); c.canonical(e,last);
            if(i!=2)c.old(old(a,START+i,prices[i]));
        }
        c.watermark(START+4);
        assertEquals(1,count(c,"oldVsCanonicalIntrabar",HIGH_MISMATCH));
        assertEquals(188710,a.candleSnapshot("WINV26").high()); assertEquals(188750,last.candle().high());
        assertFalse(c.official(last,START+300_000));
        var e=new CanonicalPriceEvent("WINV26",START+300_000,188715); var next=p.onEvent(e); c.canonical(e,next);
        c.old(CanonicalShadowComparator.asOld(next)); c.watermark(START+300_000);
        assertTrue(c.official(last,START+300_000)); assertTrue(c.officialMatches());
        assertEquals(1,count(c,"oldVsCanonicalClosed",HIGH_MISMATCH)); assertEquals(1,count(c,"oldVsOfficialClosed",HIGH_MISMATCH));
        var evidence=(List<CanonicalShadowComparator.Evidence>)c.report().get("importantDivergences");
        assertTrue(evidence.stream().anyMatch(x->x.official()!=null && x.official().candle().high()==188750 && x.context().stream().anyMatch(t->t.price()==188750)));
    }
    @Test void openOfficialRejectedAndGapDoesNotCreateArtificialCandle() {
        var c=ready();var p=processor();var e=new CanonicalPriceEvent("WINV26",START+299_999,188700);var first=p.onEvent(e);c.canonical(e,first);
        assertThrows(IllegalArgumentException.class,()->c.official(first,START+299_999));
        var after=new CanonicalPriceEvent("WINV26",START+600_000,188705);var next=p.onEvent(after);c.canonical(after,next);
        c.old(CanonicalShadowComparator.asOld(next));c.watermark(START+600_000);
        assertTrue(c.official(first,START+600_000));assertEquals(2L,c.report().get("m5BucketsObserved"));assertEquals(1,c.bucketsCompared());
        assertEquals(START,next.completedCandle().bucketStartTimeMsc());
    }
    @Test void evidenceIsBounded() {
        var c=ready();
        for(int i=0;i<200;i++) { long t=START+i*300_000L;var s=standard(t);c.canonical(event(s),s);
            c.old(new ShadowObservation("WINV26",t,102,s.candle(),100.,100.,true,0)); c.watermark(t); }
        assertEquals(100,((List<?>)c.report().get("importantDivergences")).size());
    }
}
