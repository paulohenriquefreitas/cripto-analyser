package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ExperimentalThreeCandlePatternDetector.*;

class ThreeCandleWickTest {
    static final long START=java.time.LocalDate.parse("2026-10-30").toEpochDay()*86400000L+9*3600000L;
    CandleUpdate bar(int index,double open,double high,double low,double close){return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START+index*300000L,open,high,low,close),5,true,START+(index+1)*300000L);}
    ExperimentalThreeCandlePatternDetector setup(boolean sell){
        var d=new ExperimentalThreeCandlePatternDetector("WINZ26","2026-10-30",Parameters.win());
        double price=sell?1100:900;d.references(List.of(new Region("known","TEST",price,price,START-1,"Test")));
        d.context(START,new ThreeCandleMarketContext.Snapshot(sell?"UP":"DOWN","UNDEFINED","UNDEFINED","TEST",START,null,null));
        d.onCandle(sell?bar(0,1000,1110,990,1100):bar(0,1000,1010,890,900));
        d.onCandle(sell?bar(1,1080,1120,1050,1090):bar(1,920,950,880,910));return d;
    }
    void minute(ExperimentalThreeCandlePatternDetector d,int i,double open,double high,double low,double close,double cumHigh,double cumLow){
        long t=START+600000L+i*60000L;var m=new Minute(t,open,high,low,close);
        d.onMinute(m,new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",START+600000L,1000,cumHigh,cumLow,close),5,false,m.availableAtMsc()));
    }
    Candidate current(ExperimentalThreeCandlePatternDetector d,int i){return d.snapshot(START+600000L+(i+1)*60000L,"SYNTHETIC").candidates().getFirst();}
    @Test void inclusiveMinimumOfThirtyPointsIsSixTicksAndOffsetRemainsTwentyPoints(){
        assertThat(Parameters.win().minimumWickPoints()).isEqualTo(30);assertThat(Parameters.win().orderOffsetPoints()).isEqualTo(20);
        for(int points:List.of(25,29,30,31,35)) {
            var d=setup(false);minute(d,0,1000,1005,1000-points,1000,1005,1000-points);var c=current(d,0);
            assertThat(c.trigger().state()).isEqualTo(points<30?"NAO_POSICIONADO":"AGUARDANDO");
            if(points>=30){assertThat(c.rejectionWick().points()).isEqualTo(points);assertThat(c.rejectionWick().ticks()).isEqualTo(points/5.0);assertThat(c.trigger().level()).isEqualTo(1020);}
        }
    }
    @Test void measuresWickNotExcursionAndWaitsForQualificationBeforeAnyLaterEntry(){
        var d=setup(false);minute(d,0,1000,1005,960,980,1005,960);
        assertThat(current(d,0).trigger().state()).isEqualTo("NAO_POSICIONADO"); // 40pt excursion, only 20pt wick.
        minute(d,1,980,995,975,990,1005,960);var c=current(d,1);
        assertThat(c.rejectionWick().points()).isEqualTo(30);assertThat(c.trigger().placedAtMsc()).isEqualTo(START+720000);
        assertThat(c.trigger().reachedAtMsc()).isNull();
        minute(d,2,990,1030,985,1025,1030,960);assertThat(current(d,2).trigger().state()).isEqualTo("ALCANCADO_HIPOTETICO");
    }
    @Test void sellIsMirroredAndLateOrMissingEvidenceCannotInventAnEntry(){
        var sell=setup(true);minute(sell,0,1000,1030,995,1000,1030,995);
        assertThat(current(sell,0).rejectionWick().points()).isEqualTo(30);assertThat(current(sell,0).trigger().level()).isEqualTo(980);
        minute(sell,1,1000,1005,975,980,1030,975);assertThat(current(sell,1).trigger().state()).isEqualTo("ALCANCADO_HIPOTETICO");
        var late=setup(false);minute(late,0,1000,1040,800,1030,1040,800);
        assertThat(current(late,0).trigger().state()).isEqualTo("INDETERMINADO");assertThat(current(late,0).trigger().placedAtMsc()).isNull();
        var gap=setup(false);minute(gap,1,1000,1005,850,1000,1005,850);
        assertThat(current(gap,1).trigger().placedAtMsc()).isNull();
    }
}
