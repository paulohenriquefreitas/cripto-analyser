package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.application.win.strategy.ConfirmedPivotDetector;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleStructureTest {
    private CandleUpdate bar(int i,double high,double low,double close,boolean closed) {
        long start=i*300000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",start,100,high,low,close),5,closed,start+(closed?300000:1000));
    }
    private List<CandleUpdate> outside() {
        return List.of(bar(0,105,95,100,true),bar(1,110,90,100,true),bar(2,120,80,100,true),
                bar(3,110,90,100,true),bar(4,105,95,100,true));
    }
    @Test void strictPivotsHaveOriginalAndConfirmationTimesAndCandleRegion() {
        var p=new CandlePipeline("WINZ26",5); outside().forEach(p::accept);
        var levels=p.structure(6).levels(); assertThat(levels).hasSize(2);
        assertThat(levels).extracting(StructuralLevel::type).containsExactlyInAnyOrder(StructuralLevel.Type.TOP,StructuralLevel.Type.BOTTOM);
        assertThat(levels).extracting(StructuralLevel::price).containsExactlyInAnyOrder(120d,80d);
        for(var level:levels) {
            assertThat(level.extremeTimeMsc()).isEqualTo(600000); assertThat(level.confirmedAtMsc()).isEqualTo(1500000);
            assertThat(level.region()).isEqualTo(new StructuralLevel.Region(120,80,100,"PIVOT_CANDLE"));
            assertThat(level.relevance()).isEqualTo("PROXIMITY");
        }
    }
    @Test void formingRightCandleCannotConfirmAndDuplicateClosuresDoNotDuplicateLevels() {
        var p=new CandlePipeline("WINZ26",5); outside().subList(0,4).forEach(p::accept);
        p.accept(bar(4,105,95,100,false)); assertThat(p.activeLevels()).isEmpty();
        p.accept(outside().get(4)); assertThat(p.activeLevels()).hasSize(2);
        p.accept(outside().get(4)); assertThat(p.activeLevels()).hasSize(2);
    }
    @Test void everyReplayPrefixMatchesLiveAndResetDropsUnknownLevels() {
        var bars=outside(); var replay=new ThreeCandleReplay("WINZ26",5,bars,0); var live=new CandlePipeline("WINZ26",5);
        for(int i=0;i<bars.size();i++) {
            replay.next(); live.accept(bars.get(i));
            assertThat(replay.pipeline().structure(6)).isEqualTo(live.structure(6));
            if(i<4) assertThat(replay.pipeline().activeLevels()).isEmpty();
        }
        replay.reset(); assertThat(replay.pipeline().activeLevels()).isEmpty();
        for(int i=0;i<4;i++) replay.next(); assertThat(replay.pipeline().activeLevels()).isEmpty();
    }
    @Test void onlyClosedBreaksMarkLevelsAndEqualityDoesNot() {
        var p=new CandlePipeline("WINZ26",5); outside().forEach(p::accept);
        p.accept(bar(5,130,70,100,false)); assertThat(p.activeLevels()).hasSize(2);
        p.accept(bar(5,130,70,120,true)); assertThat(p.activeLevels()).hasSize(2);
        p.accept(bar(6,130,90,121,true));
        assertThat(p.activeLevels()).anyMatch(l -> l.type()==StructuralLevel.Type.TOP && l.state()==StructuralLevel.State.BROKEN && l.brokenAtMsc()==2100000L);
        assertThat(p.activeLevels()).anyMatch(l -> l.type()==StructuralLevel.Type.BOTTOM);
    }
    @Test void equalNeighborExtremaAreNotStrictPivots() {
        var p=new CandlePipeline("WINZ26",5);
        for(int i=0;i<5;i++) p.accept(bar(i,110,90,100,true));
        assertThat(p.activeLevels()).isEmpty();
    }
    @Test void allSessionPivotsRemainVisibleWithoutQuotaOrPriceGrouping() {
        var p=new CandlePipeline("WINZ26",5); outside().forEach(p::accept);
        p.accept(bar(5,115,90,100,true)); p.accept(bar(6,125,90,100,true));
        p.accept(bar(7,110,90,100,true)); p.accept(bar(8,105,95,100,true));
        p.accept(bar(9,130,95,126,true));
        assertThat(p.structure(1).levels()).containsExactlyElementsOf(p.structure(12).levels());
        assertThat(p.structure(1).levels()).extracting(StructuralLevel::price).contains(120d,125d,80d);
        assertThat(p.structure(1).levels()).anyMatch(l -> l.price()==120 && l.state()==StructuralLevel.State.BROKEN);
        var remembered=p.activeLevels();
        var restored=new CandlePipeline("WINZ26",5); restored.rememberLevels(remembered);
        p.snapshot().forEach(restored::accept);
        assertThat(restored.structure(1)).isEqualTo(p.structure(1));
        assertThat(restored.activeLevels()).extracting(StructuralLevel::id).doesNotHaveDuplicates();
    }
    @Test void sessionsKeepSeparateMemoryAndDoNotConfirmAcrossOvernightBoundary() {
        var p=new CandlePipeline("WINZ26",5); outside().forEach(p::accept);
        assertThat(p.structure(1).levels()).hasSize(2);
        // A forming bar does not reset the closed-candle detector or confirm anything.
        p.accept(bar(288,105,95,100,false));
        p.accept(bar(288,105,95,100,true));
        assertThat(p.structure(1).levels()).isEmpty();
        assertThat(p.activeLevels()).hasSize(2);
        for (int i=1;i<5;i++) p.accept(bar(288+i,i==2?120:110,i==2?80:90,100,true));
        assertThat(p.structure(1).levels()).hasSize(2).allMatch(l -> l.extremeTimeMsc()/86400000L==1);
        assertThat(p.activeLevels()).hasSize(4);
    }
    @Test void m15DoesNotReuseM5Levels() {
        var p=new CandlePipeline("WINZ26",15);
        for(int i=0;i<5;i++) p.accept(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",i*900000L,100,110,90,100),15,true,(i+1)*900000L));
        assertThat(p.structure(6).supported()).isFalse(); assertThat(p.activeLevels()).isEmpty();
    }
}
