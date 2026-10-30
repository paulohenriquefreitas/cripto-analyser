package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.domain.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ThreeCandleH1Test {
    private CandleUpdate bar(long time, double high, double low, double close, boolean closed) {
        return new CandleUpdate(new IntrabarCandleSnapshot("WINZ26",time,100,high,low,close),5,closed,time+(closed?300000:1000));
    }
    private List<CandleUpdate> hours() {
        var bars=new ArrayList<CandleUpdate>();
        for(int hour=0;hour<7;hour++) for(int i=0;i<12;i++) {
            double span=(hour==2?20:10)-Math.abs(6-i);
            bars.add(bar((9L+hour)*3600000+i*300000L,100+span,100-span,100,true));
        }
        return bars;
    }
    @Test void hourlyConfirmationIsCausalAndSharesMemoryWithM5WithoutCollapsingOrigins() {
        var bars=hours(); var live=new CandlePipeline("WINZ26",5);
        var replay=new ThreeCandleReplay("WINZ26",5,bars,0);
        for(int i=0;i<bars.size();i++) {
            live.accept(bars.get(i)); replay.next();
            assertThat(live.structure(6)).isEqualTo(replay.pipeline().structure(6));
            long known=bars.get(i).availableAtMsc();
            assertThat(live.activeLevels()).allMatch(l -> l.confirmedAtMsc()<=known);
            if(i<59) assertThat(live.activeLevels()).noneMatch(l -> l.timeframeMinutes()==60);
            if(i==59) {
                var hourly=live.activeLevels().stream().filter(l -> l.timeframeMinutes()==60).toList();
                assertThat(hourly).hasSize(2);
                assertThat(hourly).allMatch(l -> l.extremeTimeMsc()==11*3600000L && l.confirmedAtMsc()==14*3600000L);
                assertThat(live.structure(12).levels().stream().filter(l -> l.price()==120).map(StructuralLevel::timeframeMinutes))
                        .containsExactlyInAnyOrder(5,60);
            }
        }
        replay.reset(); assertThat(replay.pipeline().activeLevels()).isEmpty();
    }
    @Test void formingHourlyRightCandleDoesNotConfirmAndH1BreakRequiresHourlyClose() {
        var p=new CandlePipeline("WINZ26",5); var bars=hours();
        bars.subList(0,59).forEach(p::accept);
        var last=bars.get(59); p.accept(bar(last.candle().bucketStartTimeMsc(),110,90,100,false));
        assertThat(p.activeLevels()).noneMatch(l -> l.timeframeMinutes()==60);
        p.accept(last);
        p.accept(bar(14*3600000L,130,90,121,true));
        assertThat(p.activeLevels()).anyMatch(l -> l.timeframeMinutes()==60 && l.type()==StructuralLevel.Type.TOP && l.state()==StructuralLevel.State.CONFIRMED);
        for(int i=1;i<12;i++) p.accept(bar(14*3600000L+i*300000L,130,90,121,true));
        assertThat(p.activeLevels()).anyMatch(l -> l.timeframeMinutes()==60 && l.type()==StructuralLevel.Type.TOP && l.state()==StructuralLevel.State.BROKEN);
        assertThat(p.structure(1).levels()).anyMatch(l -> l.timeframeMinutes()==60 && l.price()==120);
        p.accept(bar(15*3600000L,125,75,81,true));
        assertThat(p.structure(1).levels()).anyMatch(l -> l.timeframeMinutes()==60 && l.price()==80);
        p.accept(bar(15*3600000L+300000,125,85,119,true));
        assertThat(p.structure(1).levels()).anyMatch(l -> l.timeframeMinutes()==60 && l.price()==120 && l.state()==StructuralLevel.State.BROKEN);
    }
    @Test void nativeHoursDoNotShiftWithDisplayZoneAndSparseSessionTailNeverUsesFuture() {
        var h=new H1CandleAggregator();
        assertThat(h.accept(bar(9*3600000L+300000,110,90,100,true))).isEmpty();
        assertThat(h.accept(bar(10*3600000L,110,90,100,true))).isEmpty(); // truncated leading hour skipped
        var closed=h.accept(bar(11*3600000L,115,85,100,true)).orElseThrow();
        assertThat(closed.candle().bucketStartTimeMsc()).isEqualTo(10*3600000L);
        assertThat(closed.availableAtMsc()).isEqualTo(11*3600000L+300000);
        var tail=new H1CandleAggregator();
        assertThat(tail.accept(bar(18*3600000L,110,90,100,true))).isEmpty();
        assertThat(tail.accept(bar(18*3600000L+1800000,120,80,100,true))).isEmpty();
        var nextDay=tail.accept(bar(33*3600000L,110,90,100,true)).orElseThrow();
        assertThat(nextDay.candle().bucketStartTimeMsc()).isEqualTo(18*3600000L);
        assertThat(nextDay.availableAtMsc()).isEqualTo(33*3600000L+300000);
        assertThat(nextDay.candle().high()).isEqualTo(120);
    }
    @Test void missingFirstM5SlotOfLaterHourUsesNativeHourAndFirstActualOpen() {
        var h=new H1CandleAggregator();
        for(int i=0;i<12;i++) h.accept(bar(9*3600000L+i*300000L,110,90,100,true));
        assertThat(h.accept(bar(10*3600000L+300000L,120,80,100,true))).isEmpty();
        var hour=h.accept(bar(10*3600000L+3300000L,115,85,100,true)).orElseThrow();
        assertThat(hour.candle().bucketStartTimeMsc()).isEqualTo(10*3600000L);
        assertThat(hour.candle().high()).isEqualTo(120);
    }
    @Test void liveBootstrapsOlderM5CandlesOnceWithoutOpeningAnotherStream() throws Exception {
        var reader=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase.class);
        var handler=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler.class);
        var client=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient.class);
        var rows=new ArrayList<br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle>();
        for(int day=0;day<4;day++) for(var update:hours()) {
            var c=update.candle(); rows.add(new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle(
                    (c.bucketStartTimeMsc()+day*86400000L)/1000,c.open(),c.high(),c.low(),c.close(),0,0));
        }
        var recent=rows.subList(rows.size()-100,rows.size()).stream().map(c ->
                new br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle(c,null,null,null,null)).toList();
        org.mockito.Mockito.when(reader.execute()).thenReturn(recent);
        org.mockito.Mockito.when(client.readCandles()).thenReturn(rows);
        var last=rows.getLast(); long time=last.time()*1000+1000;
        org.mockito.Mockito.when(handler.latestAvailableCandle()).thenReturn(Optional.of(
                new br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler.SharedCandle(
                    new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Tick(time/1000,time,100,100,100),
                    new IntrabarCandleSnapshot("WINZ26",last.time()*1000,last.open(),last.high(),last.low(),last.close()))));
        var live=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleLive(
                reader,handler,new br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Symbol("WINZ26"),client,java.time.Clock.fixed(java.time.Instant.EPOCH,java.time.ZoneOffset.UTC));
        var first=live.snapshot("WINZ26",5);
        assertThat(first.structure().h1History().sufficient()).isTrue();
        assertThat(first.structure().levels()).anyMatch(l -> l.timeframeMinutes()==60);
        assertThat(live.snapshot("WINZ26",5).structure()).isEqualTo(first.structure());
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).readCandles();
        org.mockito.Mockito.verify(reader,org.mockito.Mockito.times(1)).execute();
    }
    @Test void warmupReportsTwoPriorTradingSessionsAndLeftSideHistoryAcrossWeekend() {
        var p=new CandlePipeline("WINZ26",5);
        for(int day : new int[]{0,3,4,5}) for(var source:hours()) {
            var c=source.candle(); long offset=day*86400000L;
            p.accept(bar(c.bucketStartTimeMsc()+offset,c.high(),c.low(),c.close(),true));
        }
        assertThat(p.structure(6).h1History().sufficient()).isTrue();
        assertThat(p.structure(6).h1History().previousSessions()).isEqualTo(3);
    }
    @Test @EnabledIfSystemProperty(named="threecandles.h1Reference",matches=".+")
    void comparesAggregatedHoursAndAllConfirmedPivotsWithNativeMt5H1() throws Exception {
        var json=new ObjectMapper(); var data=json.readTree(Path.of(System.getProperty("threecandles.h1Reference")).toFile());
        String symbol=data.path("symbol").asText(); var bars=new ArrayList<CandleUpdate>();
        var h=new H1CandleAggregator(); var aggregated=new TreeMap<Long,CandleUpdate>();
        for(var c:data.path("series").path("M5")) {
            long time=c.path("time").asLong()*1000;
            var update=new CandleUpdate(new IntrabarCandleSnapshot(symbol,time,c.path("open").asDouble(),c.path("high").asDouble(),
                    c.path("low").asDouble(),c.path("close").asDouble()),5,true,time+300000);
            bars.add(update); h.accept(update).ifPresent(v -> aggregated.put(v.candle().bucketStartTimeMsc(),v));
        }
        var nativeBars=data.path("series").path("H1");
        for(var c:nativeBars) {
            var actual=aggregated.get(c.path("time").asLong()*1000);
            if(actual==null) continue; // final unproven session-tail remains unavailable
            assertThat(actual.candle().open()).isEqualTo(c.path("open").asDouble());
            assertThat(actual.candle().high()).isEqualTo(c.path("high").asDouble());
            assertThat(actual.candle().low()).isEqualTo(c.path("low").asDouble());
            assertThat(actual.candle().close()).isEqualTo(c.path("close").asDouble());
        }
        assertThat(aggregated.size()).isEqualTo(nativeBars.size()-1);
        var expected=new HashSet<String>();
        for(int i=2;i<nativeBars.size()-2;i++) {
            var origin=nativeBars.get(i); var right=aggregated.get(nativeBars.get(i+2).path("time").asLong()*1000);
            if(right==null) continue;
            boolean top=true,bottom=true;
            for(int j=i-2;j<=i+2;j++) if(j!=i) {
                top &= origin.path("high").asDouble()>nativeBars.get(j).path("high").asDouble();
                bottom &= origin.path("low").asDouble()<nativeBars.get(j).path("low").asDouble();
            }
            if(top) expected.add("TOP:"+origin.path("time").asLong()*1000+":"+origin.path("high").asDouble());
            if(bottom) expected.add("BOTTOM:"+origin.path("time").asLong()*1000+":"+origin.path("low").asDouble());
        }
        var p=new CandlePipeline(symbol,5); bars.forEach(p::accept);
        var actual=new HashSet<String>();
        p.activeLevels().stream().filter(l -> l.timeframeMinutes()==60).forEach(l -> actual.add(l.type()+":"+l.extremeTimeMsc()+":"+l.price()));
        assertThat(actual).isEqualTo(expected);
        if(System.getProperty("threecandles.h1ValidationFile")!=null)
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getProperty("threecandles.h1ValidationFile")).toFile(),
                    Map.of("source","MT5 native H1 compared with M5 aggregation", "symbol",symbol,
                        "nativeHours",nativeBars.size(),"comparedHours",aggregated.size(),"unprovenTrailingHours",1,
                        "confirmedPivots",actual.size(),"discrepancies",0,"levels",p.activeLevels().stream().filter(l -> l.timeframeMinutes()==60).toList()));
        System.out.println("H1_MT5_COMPARISON bars="+aggregated.size()+" confirmedPivots="+actual.size()+" discrepancies=0");
    }
}
