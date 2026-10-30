package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class H1SessionPreparationTest {
    private final LocalDate day=LocalDate.of(2026,10,26);
    private long at(LocalDate date,int hour) { return date.atTime(hour,0).toInstant(ZoneOffset.UTC).toEpochMilli(); }
    private List<CandleUpdate> source() {
        var result=new ArrayList<CandleUpdate>();
        double[] range={10,15,30,15,10,15,25,15,10,5};
        for(int date:new int[]{20,21,22,23,26}) for(int hour=9;hour<19;hour++) for(int m=0;m<12;m++) {
            long time=at(LocalDate.of(2026,10,date),hour)+m*300000L;
            double span=range[hour-9]+date-Math.abs(6-m)*0.05;
            result.add(new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",time,100,100+span,100-span,100),5,true,time+300000));
        }
        return result;
    }
    @Test void lastThreeTradingSessionsExcludeWeekendAndWarmupOriginsAndFutureCandles() {
        var all=source(); long opening=at(day,9);
        var plan=H1SessionPreparation.prepare("WINZ26",day,opening,all);
        var past=all.stream().filter(c->c.availableAtMsc()<=opening).toList();
        assertThat(plan).isEqualTo(H1SessionPreparation.prepare("WINZ26",day,opening,past));
        assertThat(plan.sourceSessions()).containsExactly("2026-10-21","2026-10-22","2026-10-23");
        assertThat(plan.sufficient()).isTrue();
        assertThat(plan.candidates()).isNotEmpty().allMatch(l->l.confirmedAtMsc()<=opening
                && l.extremeTimeMsc()>=at(LocalDate.of(2026,10,21),0) && l.extremeTimeMsc()<at(day,0));
        assertThat(plan.candidates()).noneMatch(l->l.extremeTimeMsc()==at(LocalDate.of(2026,10,23),18));
    }
    @Test void preparationSurvivesReplayBreaksDistanceLimitAndResetWithoutDeletingM5() {
        var all=source(); long opening=at(day,9);
        var plan=H1SessionPreparation.prepare("WINZ26",day,opening,all);
        var replay=new ThreeCandleReplay("WINZ26",5,all,opening,plan);
        var context=all.stream().filter(c -> c.candle().bucketStartTimeMsc()>=at(LocalDate.of(2026,10,21),0)
                && c.availableAtMsc()<=opening).toList();
        assertThat(replay.candles()).containsExactlyElementsOf(context);
        assertThat(replay.availableAtMsc()).isEqualTo(opening);
        for(int i=0;i<120;i++) {
            replay.next();
            assertThat(replay.pipeline().structure(1).levels().stream().filter(l->l.timeframeMinutes()==60).toList()).isEqualTo(plan.levels());
            assertThat(replay.candles()).allMatch(c->c.availableAtMsc()<=replay.availableAtMsc());
            assertThat(replay.candles()).startsWith(context.toArray(CandleUpdate[]::new));
        }
        assertThat(replay.pipeline().activeLevels()).anyMatch(l->l.timeframeMinutes()==5);
        replay.reset();
        assertThat(replay.pipeline().structure(1).preparation()).isEqualTo(plan);
        assertThat(replay.pipeline().structure(1).levels().stream().filter(l->l.timeframeMinutes()==60).toList()).isEqualTo(plan.levels());
        assertThat(replay.candles()).containsExactlyElementsOf(context);
    }
    @Test void priorSessionExtremeNeedingCurrentSessionRightBarsIsExcludedBeforeOpening() {
        long late=at(LocalDate.of(2026,10,23),18);
        var bars=source().stream().map(update->{
            var c=update.candle();
            if(c.bucketStartTimeMsc()<late || c.bucketStartTimeMsc()>=late+3600000L) return update;
            return new CandleUpdate(new IntrabarCandleSnapshot(c.symbol(),c.bucketStartTimeMsc(),100,500,1,100),5,true,update.availableAtMsc());
        }).toList();
        var plan=H1SessionPreparation.prepare("WINZ26",day,at(day,9),bars);
        assertThat(plan.candidates()).noneMatch(l->l.extremeTimeMsc()==late);
        var full=new CandlePipeline("WINZ26",5);bars.forEach(full::accept);
        assertThat(full.activeLevels()).anyMatch(l->l.timeframeMinutes()==60 && l.extremeTimeMsc()==late
                && l.price()==500 && l.confirmedAtMsc()>at(day,9));
    }
    @Test void nearbyPricesGroupOnlyVisuallyAndIncompleteHistoryIsExplicit() {
        var bars=source().stream().filter(c->c.candle().bucketStartTimeMsc()>=at(LocalDate.of(2026,10,23),0)).toList();
        var incomplete=H1SessionPreparation.prepare("WINZ26",day,at(day,9),bars);
        assertThat(incomplete.sufficient()).isFalse();
        var full=H1SessionPreparation.prepare("WINZ26",day,at(day,9),source());
        assertThat(full.levels().size()).isLessThan(full.candidates().size());
        assertThat(full.groups().values().stream().mapToInt(List::size).sum()).isEqualTo(full.candidates().size());
        assertThat(full.groups().values().stream().flatMap(List::stream).distinct().count()).isEqualTo(full.candidates().size());
    }
    @Test void liveBeforeFirstCurrentDayCandleUsesCurrentSessionAndFreezesPlanAcrossReconnect() throws Exception {
        var reader=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase.class);
        var handler=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler.class);
        var client=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient.class);
        var past=source().stream().filter(c->c.availableAtMsc()<=at(day,9)).toList();
        var rows=past.stream().map(u->{var c=u.candle();return new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle(
                c.bucketStartTimeMsc()/1000,c.open(),c.high(),c.low(),c.close(),0,0);}).toList();
        var recent=rows.subList(rows.size()-100,rows.size()).stream().map(c->new br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle(c,null,null,null,null)).toList();
        org.mockito.Mockito.when(reader.execute()).thenReturn(recent);
        org.mockito.Mockito.when(client.readCandles()).thenReturn(rows);
        org.mockito.Mockito.when(handler.latestAvailableCandle()).thenReturn(Optional.empty());
        var clock=Clock.fixed(day.atTime(8,30).toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        var live=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleLive(reader,handler,
                new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol("WINZ26"),client,clock);
        var before=live.snapshot("WINZ26",5,1);
        assertThat(before.candles()).containsExactlyElementsOf(past);
        assertThat(before.candles()).allMatch(c->c.candle().bucketStartTimeMsc()/86400000L<day.toEpochDay()
                && c.availableAtMsc()<=before.availableAtMsc());
        assertThat(before.dow().pivots()).isEmpty();
        assertThat(before.microDow().pivots()).isEmpty();
        assertThat(before.microDowFifty().references()).isEmpty();
        assertThat(before.structure().preparation().sessionDate()).isEqualTo("2026-10-26");
        assertThat(before.structure().preparation().sourceSessions()).containsExactly("2026-10-21","2026-10-22","2026-10-23");
        assertThat(before.structure().preparation().sufficient()).isTrue();
        assertThat(before.structure().preparation().candidates()).allMatch(l->l.confirmedAtMsc()<=before.availableAtMsc());
        long time=at(day,14)+1000;
        org.mockito.Mockito.when(handler.latestAvailableCandle()).thenReturn(Optional.of(new br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler.SharedCandle(
                new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick(time/1000,time,100,100,100),new IntrabarCandleSnapshot("WINZ26",at(day,14),100,110,90,100))));
        var rollover=new ArrayList<>(recent);
        rollover.add(new br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle(new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle(at(day,14)/1000,100,110,90,100,0,0),null,null,null,null));
        org.mockito.Mockito.when(reader.execute()).thenReturn(rollover);
        assertThat(live.snapshot("WINZ26",5,1).structure().preparation()).isEqualTo(before.structure().preparation());
    }
    @Test @EnabledIfSystemProperty(named="threecandles.preparationNative",matches=".+")
    void comparesOpeningPlanToNativeMt5AndWritesAuditablePreview() throws Exception {
        var json=new ObjectMapper();var data=json.readTree(Path.of(System.getProperty("threecandles.preparationNative")).toFile());
        String symbol=data.path("symbol").asText();var bars=new ArrayList<CandleUpdate>();
        for(var c:data.path("series").path("M5")) {
            long time=c.path("time").asLong()*1000;
            bars.add(new CandleUpdate(new IntrabarCandleSnapshot(symbol,time,c.path("open").asDouble(),c.path("high").asDouble(),c.path("low").asDouble(),c.path("close").asDouble()),5,true,time+300000));
        }
        long opening=at(day,9);var plan=H1SessionPreparation.prepare(symbol,day,opening,bars);
        assertThat(plan.sourceSessions()).containsExactly("2026-10-21","2026-10-22","2026-10-23");
        assertThat(plan.sufficient()).isTrue();
        var expected=new TreeMap<String,Long>();var nativeBars=data.path("series").path("H1");
        var excludedFuture=new ArrayList<String>();
        for(int i=2;i<nativeBars.size()-2;i++) {
            var c=nativeBars.get(i); long time=c.path("time").asLong()*1000;
            if(!plan.sourceSessions().contains(LocalDate.ofEpochDay(time/86400000L).toString())) continue;
            boolean top=true,bottom=true;
            for(int j=i-2;j<=i+2;j++) if(j!=i) {
                top &= c.path("high").asDouble()>nativeBars.get(j).path("high").asDouble();
                bottom &= c.path("low").asDouble()<nativeBars.get(j).path("low").asDouble();
            }
            long confirmation=nativeBars.get(i+2).path("time").asLong()*1000+3600000L;
            if(top) {String key="TOP:"+time+":"+c.path("high").asDouble();if(confirmation<=opening)expected.put(key,confirmation);else excludedFuture.add(key);}
            if(bottom) {String key="BOTTOM:"+time+":"+c.path("low").asDouble();if(confirmation<=opening)expected.put(key,confirmation);else excludedFuture.add(key);}
        }
        var actual=new TreeMap<String,Long>();
        for(var l:plan.candidates()) actual.put(l.type()+":"+l.extremeTimeMsc()+":"+l.price(),l.confirmedAtMsc());
        assertThat(actual).isEqualTo(expected);
        var replay=new ThreeCandleReplay(symbol,5,bars,opening,plan);
        while(replay.status()!=ThreeCandleReplay.Status.COMPLETED) {
            replay.next();
            assertThat(replay.pipeline().structure(1).levels().stream().filter(l->l.timeframeMinutes()==60).toList()).isEqualTo(plan.levels());
        }
        replay.reset();assertThat(replay.pipeline().structure(1).preparation()).isEqualTo(plan);
        var fresh=H1SessionPreparation.prepare(symbol,day,opening,bars.stream().filter(b->b.availableAtMsc()<=opening).toList());
        assertThat(fresh).isEqualTo(plan);
        System.out.println("H1_PREPARATION_MT5 symbol="+symbol+" sessions="+plan.sourceSessions()+" candidates="+plan.candidates().size()+" selected="+plan.levels().size()+" discrepancies=0");
        String report=System.getProperty("threecandles.preparationReport");
        if(report!=null) json.writerWithDefaultPrettyPrinter().writeValue(Path.of(report).toFile(),Map.of("preparation",plan,"nativeDiscrepancies",0,"excludedConfirmedAfterOpening",excludedFuture,"replayResetStable",true,"restartWithoutFutureStable",true));
        String preview=System.getProperty("threecandles.preparationPreview");
        if(preview!=null) {
            var view=new ThreeCandleReplay(symbol,5,bars,at(day,14),plan);
            json.writeValue(Path.of(preview).toFile(),Map.of("id","h1-preparation-preview","symbol",symbol,"timeframeMinutes",5,"status","PAUSED","speed",1,"candles",view.candles(),"structure",view.pipeline().structure(6),"availableAtMsc",view.availableAtMsc()));
        }
    }
}
