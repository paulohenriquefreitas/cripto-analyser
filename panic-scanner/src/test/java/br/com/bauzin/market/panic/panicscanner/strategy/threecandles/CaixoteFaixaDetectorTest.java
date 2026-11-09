package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.CandleUpdate;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CaixoteFaixaDetectorTest {
    final ObjectMapper json = new ObjectMapper();
    List<CandleUpdate> source() throws Exception {
        try (var input=getClass().getResourceAsStream("/threecandles/caixote-faixa/WIN_N-2026-10-M5.json")) {
            var bars=new ArrayList<CandleUpdate>();
            for(var b:json.readTree(input).path("candles")) {
                long t=b.path("time").asLong()*1000;
                bars.add(new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",t,b.path("open").asDouble(),
                        b.path("high").asDouble(),b.path("low").asDouble(),b.path("close").asDouble()),5,true,t+300000));
            }
            return bars;
        }
    }
    long at(String date,String time) {return LocalDate.parse(date).toEpochDay()*86400000L+LocalTime.parse(time).toSecondOfDay()*1000L;}
    CaixoteFaixaDetector.Snapshot study(String time) throws Exception {return CaixoteFaixaDetector.analyze("WIN$N",source(),at("2026-10-19",time));}

    @Test void matchesIndependentCorrectedRegionsAndVersionedLimitsAcrossTwentyOneSessions() throws Exception {
        var input=source();var actual=new ArrayList<CaixoteFaixaDetector.Region>();
        for(long day:input.stream().map(b->b.candle().bucketStartTimeMsc()/86400000L).distinct().toList()) {
            var bars=input.stream().filter(b->b.candle().bucketStartTimeMsc()/86400000L==day).toList();
            actual.addAll(CaixoteFaixaDetector.analyze("WIN$N",bars,bars.getLast().availableAtMsc()).regions());
        }
        assertThat(actual).hasSize(33);
        try(var expectedInput=getClass().getResourceAsStream("/threecandles/caixote-faixa/structural-regions.json")) {
            var expected=json.readTree(expectedInput);
            for(int i=0;i<actual.size();i++) {
                var a=actual.get(i);var e=expected.get(i);
                assertThat(a.startTimeMsc()).isEqualTo(e.path("start").asLong()*1000);
                assertThat(a.confirmedAtMsc()).isEqualTo(e.path("confirmed").asLong()*1000);
                assertThat(a.endTimeMsc()).isEqualTo(e.path("end").asLong()*1000);
                assertThat(a.topo()).isCloseTo(e.path("high").asDouble(),within(1e-8));
                assertThat(a.fundo()).isCloseTo(e.path("low").asDouble(),within(1e-8));
                assertThat(a.versions()).hasSize(e.path("versions").size());
                for(int j=0;j<a.versions().size();j++) {
                    var v=a.versions().get(j);var x=e.path("versions").get(j);
                    assertThat(v.knownAtMsc()).isEqualTo(x.path("known").asLong()*1000);
                    assertThat(v.topo()).isCloseTo(x.path("high").asDouble(),within(1e-8));
                    assertThat(v.fundo()).isCloseTo(x.path("low").asDouble(),within(1e-8));
                    assertThat(v.topo()-v.fundo()).isLessThanOrEqualTo(1.65*e.path("reference_range").asDouble()+1e-8);
                }
            }
        }
    }

    @Test void recoveryAndReturnCannotConfirmOnExitFromInitialClosingCoreInEitherDirection() throws Exception {
        var all=source();
        for(String time:List.of("12:40","12:45")) {
            long cutoff=at("2026-10-08",time);
            var rejected=CaixoteFaixaDetector.analyze("WIN$N",all,cutoff);
            assertThat(rejected.atual().estado()).isEqualTo(CaixoteDetector.Estado.FORA_DO_CAIXOTE);
            assertThat(rejected.atual().motivos()).contains("FECHAMENTO_FORA_NUCLEO_INICIAL");
            var mirrored=all.stream().map(b->new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",
                    b.candle().bucketStartTimeMsc(),500000-b.candle().open(),500000-b.candle().low(),
                    500000-b.candle().high(),500000-b.candle().close()),5,true,b.availableAtMsc())).toList();
            var upward=CaixoteFaixaDetector.analyze("WIN$N",mirrored,cutoff);
            assertThat(upward.atual().estado()).isEqualTo(CaixoteDetector.Estado.FORA_DO_CAIXOTE);
            assertThat(upward.atual().motivos()).contains("FECHAMENTO_FORA_NUCLEO_INICIAL");
        }
        assertThat(CaixoteFaixaDetector.analyze("WIN$N",all,at("2026-10-08","12:55")).regions()).isEmpty();
    }

    @Test void firstMarkedRangeSurvivesSingleExcursionsAndEndsOnlyAfterTwoKnownCloses() throws Exception {
        var active=study("16:35");
        assertThat(active.atual().estado()).isEqualTo(CaixoteDetector.Estado.CAIXOTE);
        assertThat(active.atual().confirmadoEmMsc()).isEqualTo(at("2026-10-19","15:35"));
        assertThat(active.atual().inicioObservadoMsc()).isEqualTo(at("2026-10-19","15:05"));
        assertThat(study("16:40").diagnostico().fechamentosFora()).isEqualTo(1);
        assertThat(study("16:40").atual().estado()).isEqualTo(CaixoteDetector.Estado.CAIXOTE);
        var ended=study("16:45");
        assertThat(ended.atual().estado()).isEqualTo(CaixoteDetector.Estado.FORA_DO_CAIXOTE);
        assertThat(ended.regions().getLast().exitReason()).isEqualTo("M5_TWO_CLOSES_BELOW");
        assertThat(ended.regions().getLast().endedAtMsc()).isEqualTo(at("2026-10-19","16:45"));
    }

    @Test void secondMarkedRangeConfirmsFromSmallBodiesAndClosesWithoutAtrGate() throws Exception {
        assertThat(study("17:45").regions()).hasSize(1);
        var s=study("17:50");
        assertThat(s.regions()).hasSize(2);
        assertThat(s.atual().estado()).isEqualTo(CaixoteDetector.Estado.CAIXOTE);
        assertThat(s.atual().topo()).isEqualTo(209935);
        assertThat(s.atual().fundo()).isEqualTo(209715);
        assertThat(s.atual().fibo50()).isEqualTo(209825);
        assertThat(s.atual().cruzamentos50()).isEqualTo(2);
        assertThat(s.diagnostico().corpoMedio()).isEqualTo(35);
        assertThat(s.atual().atr20()).isNull();
        assertThat(study("17:55").diagnostico().fechamentosFora()).isEqualTo(1);
        assertThat(study("18:00").regions().getLast().exitReason()).isEqualTo("M5_TWO_CLOSES_BELOW");
    }

    @Test void boundsDoNotAbsorbOutsideClosesAndAreVersionedOnlyAfterKnownReturn() throws Exception {
        var pending=study("15:45");
        assertThat(pending.diagnostico().fechamentosFora()).isEqualTo(1);
        assertThat(pending.atual().topo()).isEqualTo(209445);
        var returned=study("15:50");
        assertThat(returned.diagnostico().fechamentosFora()).isZero();
        assertThat(returned.atual().topo()).isEqualTo(209566);
        assertThat(pending.atual().topo()).isEqualTo(209445); // Earlier immutable snapshot cannot repaint.
        assertThat(returned.regions().getLast().versions().getLast().knownAtMsc()).isEqualTo(at("2026-10-19","15:50"));
    }

    @Test void everyPrefixOfMarkedSessionMatchesReplayReconstructionAndRejectsFormingFuture() throws Exception {
        var all=source();var day=all.stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==LocalDate.parse("2026-10-19").toEpochDay()).toList();
        var engine=new CaixoteFaixaDetector("WIN$N");
        for(var bar:day) assertThat(engine.onCandleFechado(bar)).isEqualTo(CaixoteFaixaDetector.analyze("WIN$N",all,bar.availableAtMsc()));
        var before=study("15:45");
        var partial=new CandleUpdate(new IntrabarCandleSnapshot("WIN$N",at("2026-10-19","15:45"),209440,210000,208000,209440),5,false,at("2026-10-19","15:46"));
        var extended=new ArrayList<>(all);extended.add(partial);
        assertThat(CaixoteFaixaDetector.analyze("WIN$N",extended,at("2026-10-19","15:45"))).isEqualTo(before);
    }

    @Test void duplicateIsIdempotentInvalidUpdatesAreAtomicAndGapResetsReference() throws Exception {
        var day=source().stream().filter(c->c.candle().bucketStartTimeMsc()/86400000L==LocalDate.parse("2026-10-19").toEpochDay()).toList();
        var engine=new CaixoteFaixaDetector("WIN$N");
        for(var b:day)if(b.availableAtMsc()<=at("2026-10-19","15:50"))engine.onCandleFechado(b);
        var last=day.stream().filter(b->b.availableAtMsc()==at("2026-10-19","15:50")).findFirst().orElseThrow();
        var before=engine.snapshot(last.availableAtMsc());
        assertThat(engine.onCandleFechado(last)).isEqualTo(before);
        assertThatThrownBy(()->engine.onCandleFechado(day.getFirst())).isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.snapshot(last.availableAtMsc())).isEqualTo(before);
        var gap=day.stream().filter(b->b.availableAtMsc()==at("2026-10-19","16:05")).findFirst().orElseThrow();
        var after=engine.onCandleFechado(gap);
        assertThat(after.regions().getLast().exitReason()).isEqualTo("DATA_GAP");
        assertThat(after.atual().estado()).isEqualTo(CaixoteDetector.Estado.FORA_DO_CAIXOTE);
        assertThat(after.diagnostico()).isNull();
    }

}
