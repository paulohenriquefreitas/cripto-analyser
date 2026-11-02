package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.ThreeCandleVisualAudit;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.*;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Stage assignments below are visual fixtures, NOT a proposed Alvo/Nega/Testa definition. */
class ThreeCandleVisualAuditTest {
    static final String DAY="2026-10-30",CRITERION="VISUAL_FIXTURE_ONLY_NOT_RECOGNITION";
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA=Path.of("src/test/resources/threecandles/opening-structure");
    static List<CandleUpdate> bars() throws Exception {
        var result=new ArrayList<CandleUpdate>();
        for(var c:JSON.readTree(DATA.resolve("WINZ26-2026-10-30-M5.json").toFile())) {
            long t=c.path("time").asLong()*1000;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",t,c.path("open").asDouble(),c.path("high").asDouble(),
                c.path("low").asDouble(),c.path("close").asDouble()),5,true,t+300000));
        }
        return result;
    }
    static ThreeCandleVisualAudit.Stage stage(int number,CandleUpdate observation) {
        return new ThreeCandleVisualAudit.Stage(number,ThreeCandleVisualAudit.Role.values()[number-1],observation,
            observation.candle().close(),observation.availableAtMsc(),"Visual fixture only; recognition rule not supplied");
    }
    @Test void ledgerRevealsOneTwoThreeInOrderWithoutRepaintingAndRejectsMissingEvidence() throws Exception {
        var bars=bars();var ledger=new ThreeCandleVisualAudit("WINZ26",DAY,CRITERION);
        long t=bars.getFirst().candle().bucketStartTimeMsc();
        assertThat(ThreeCandleVisualAudit.pending("WINZ26",DAY,t).patterns()).isEmpty();
        assertThatThrownBy(()->new ThreeCandleVisualAudit("WINZ26",DAY,null)).isInstanceOf(IllegalArgumentException.class);
        for(var direction:ThreeCandleVisualAudit.Direction.values()) {
            for(int i=1;i<=3;i++)ledger.record(direction.name(),direction,stage(i,bars.get(i+2)));
        }
        assertThat(ledger.snapshot(bars.get(3).availableAtMsc()-1).patterns()).isEmpty();
        for(int i=1;i<=3;i++) {
            var s=ledger.snapshot(bars.get(i+2).availableAtMsc());
            assertThat(s.patterns()).hasSize(2);
            for(var p:s.patterns())assertThat(p.stages()).hasSize(i);
        }
        var frozen=ledger.snapshot(bars.get(3).availableAtMsc());
        ledger.record("BUY",ThreeCandleVisualAudit.Direction.BUY,stage(1,bars.get(3)));
        assertThat(ledger.snapshot(bars.get(3).availableAtMsc())).isEqualTo(frozen);
        assertThatThrownBy(()->ledger.record("BUY",ThreeCandleVisualAudit.Direction.SELL,stage(1,bars.get(3)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ledger.record("BUY",ThreeCandleVisualAudit.Direction.BUY,stage(1,bars.get(4)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ledger.record("missing",ThreeCandleVisualAudit.Direction.BUY,stage(2,bars.get(4)))).isInstanceOf(IllegalArgumentException.class);
        var bad=new ThreeCandleVisualAudit.Stage(1,ThreeCandleVisualAudit.Role.ALVO,bars.get(3),100,bars.get(3).availableAtMsc()-1,"future observation");
        assertThatThrownBy(()->ledger.record("bad",ThreeCandleVisualAudit.Direction.BUY,bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void visualEvidencePrefixesAgreeInClosedReplayReconstructionAndNativeM1() throws Exception {
        var bars=bars();long opening=bars.getFirst().candle().bucketStartTimeMsc();
        var minutes=Arrays.asList(JSON.readValue(DATA.resolve("WINZ26-2026-10-30-M1.json").toFile(),Mt5Candle[].class));
        var updates=ProgressiveM1History.build("WINZ26",bars,minutes,opening);
        for(boolean progressive:List.of(false,true)) {
            var input=progressive?updates:bars;
            var replay=new ThreeCandleReplay("WINZ26",5,input,opening,null,progressive?ThreeCandleReplay.Mode.PROGRESSIVE_M1:ThreeCandleReplay.Mode.CLOSED_CANDLE);
            var full=new ThreeCandleVisualAudit("WINZ26",DAY,CRITERION);
            var incremental=new ThreeCandleVisualAudit("WINZ26",DAY,CRITERION);
            for(int i=1;i<=3;i++)full.record("fixture",ThreeCandleVisualAudit.Direction.BUY,stage(i,bars.get(i+2)));
            for(var update:input) {
                replay.next();long at=replay.availableAtMsc();
                for(int i=1;i<=3;i++)if(bars.get(i+2).availableAtMsc()<=at)
                    incremental.record("fixture",ThreeCandleVisualAudit.Direction.BUY,stage(i,bars.get(i+2)));
                assertThat(incremental.snapshot(at)).isEqualTo(full.snapshot(at));
                assertThat(full.snapshot(at).patterns()).allMatch(p->p.stages().stream().allMatch(s->s.availableAtMsc()<=at));
            }
        }
    }
}
