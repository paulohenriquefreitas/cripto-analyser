package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.ShadowObservation;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyShadowCaptureTest {
    @Test void disabledByDefaultAndSessionIsIsolated() {
        var c=new LegacyShadowCapture(); assertFalse(c.enabled());var token=c.open();assertTrue(c.enabled());
        assertThrows(IllegalStateException.class,c::open);assertThrows(IllegalArgumentException.class,()->c.poll("wrong"));
        c.close(token);assertFalse(c.enabled());assertNotEquals(token,c.open());
    }
    @Test void boundedQueueReportsLossWithoutThrowingIntoLive() {
        var c=new LegacyShadowCapture();var token=c.open();var s=new ShadowObservation("WINV26",1,100,null,null,null,true,0);
        for(int i=0;i<LegacyShadowCapture.CAPACITY+5;i++)c.offer(s);
        var batch=c.poll(token);assertEquals(5,batch.dropped());assertEquals(5000,batch.observations().size());assertEquals(15000,batch.queued());
        c.close(token);
    }
    @Test void tapObservesActualAdapterAndExcludesRestReplay() {
        var a=new Ta4jMt5Sma9Adapter();var bars=new ArrayList<Mt5Candle>();
        for(int i=0;i<21;i++)bars.add(new Mt5Candle(i*300L,100,100,100,100,0,0));
        a.synchronize(bars);var token=a.shadowCapture().open();var tick=new Mt5Tick(6000,6_000_001,101,101,101);
        var current=a.onTick(tick);var captured=a.shadowCapture().poll(token).observations().getFirst();
        assertEquals(current.value(),captured.sma9());assertEquals(a.candleSnapshot("WINV26"),captured.candle());assertTrue(captured.freshAfterSync());
        a.synchronize(bars);a.onTick(tick);assertFalse(a.shadowCapture().poll(token).observations().getFirst().freshAfterSync());
        a.shadowCapture().close(token);assertNotNull(a.onTick(new Mt5Tick(6000,6_000_002,102,102,102)));
    }
}
