package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DowAnticipationStudyTest {
    static final long START=java.time.LocalDate.parse("2026-10-06").toEpochDay()*86400000L+9*3600000;
    CandleUpdate bar(int i,double high,double low) {
        long at=START+i*300000L;
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",at,100,high,low,100),5,true,at+300000);
    }
    List<CandleUpdate> top(){return List.of(bar(0,110,90),bar(1,120,90),bar(2,150,90),bar(3,130,90));}
    @Test void availableAtCloseOfCBeforeDAndDoesNotPolluteConfirmedDow() {
        var pipeline=new CandlePipeline("WINZ26",5);
        top().subList(0,3).forEach(pipeline::accept);assertThat(pipeline.structure(6).earlyPivots()).isEmpty();
        pipeline.accept(new CandleUpdate(top().get(3).candle(),5,false,START+950000));
        assertThat(pipeline.structure(6).earlyPivots()).isEmpty();
        pipeline.accept(top().get(3));var early=pipeline.structure(6).earlyPivots().getFirst();
        assertThat(early.status()).isEqualTo("CANDIDATE_2_1");assertThat(early.availableAtMsc()).isEqualTo(START+1200000);
        assertThat(early.origin()).isEqualTo(top().get(2));assertThat(early.rightD()).isNull();
        assertThat(pipeline.structure(6).confirmedPivots()).isEmpty();
        assertThat(DowStructureEngine.analyze("WINZ26",pipeline.snapshot(),pipeline.structure(6),early.availableAtMsc()).pivots()).isEmpty();
        pipeline.accept(bar(4,125,90));var confirmed=pipeline.structure(6).earlyPivots().getFirst();
        assertThat(confirmed.status()).isEqualTo("CONFIRMED_2_2");assertThat(confirmed.availableAtMsc()).isEqualTo(early.availableAtMsc());
        assertThat(confirmed.resolvedAtMsc()).isEqualTo(START+1500000);assertThat(pipeline.structure(6).confirmedPivots()).hasSize(1);
        var rebuilt=new CandlePipeline("WINZ26",5);pipeline.snapshot().forEach(rebuilt::accept);
        assertThat(rebuilt.structure(6)).isEqualTo(pipeline.structure(6));
    }
    @Test void equalityOrHigherDInvalidatesCandidateWithoutInventingPivot() {
        for(double high:List.of(150d,160d)) {
            var p=new CandlePipeline("WINZ26",5);top().forEach(p::accept);p.accept(bar(4,high,90));
            assertThat(p.structure(6).earlyPivots().getFirst().status()).isEqualTo("INVALIDATED");
            assertThat(p.structure(6).confirmedPivots()).isEmpty();
        }
        var equal=new DowAnticipationStudy();List.of(bar(0,110,90),bar(1,120,90),bar(2,150,90),bar(3,150,90)).forEach(equal::advance);
        assertThat(equal.snapshot()).isEmpty();
    }
    @Test void bottomIsSymmetricAndGapsAndNewSessionsDoNotConfirmEarlyCandidate() {
        var bottom=new DowAnticipationStudy();List.of(bar(0,110,90),bar(1,110,80),bar(2,110,50),bar(3,110,70)).forEach(bottom::advance);
        assertThat(bottom.snapshot().getFirst().type()).isEqualTo("BOTTOM");bottom.advance(bar(4,110,60));
        assertThat(bottom.snapshot().getFirst().status()).isEqualTo("CONFIRMED_2_2");
        var gap=new DowAnticipationStudy();top().forEach(gap::advance);gap.advance(bar(5,125,90));
        assertThat(gap.snapshot().getFirst().status()).isEqualTo("INCONCLUSIVE_GAP");
        gap.advance(bar(288,110,90));assertThat(gap.snapshot()).isEmpty();
    }
}
