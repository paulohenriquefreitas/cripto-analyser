package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application.H1SessionPreparation;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThreeCandleHistoryLoaderTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final LocalDate day=LocalDate.of(2026,10,16);
    final Mt5ProcessClient mt5=mock(Mt5ProcessClient.class);
    private ThreeCandleHistoryLoader loader(Mt5ProcessClient client) {
        var paths=new ReplayHistoryProvider(root+"/{symbol}/{date}.ndjson","unused","unused");
        return new ThreeCandleHistoryLoader(new ThreeCandleHistory(paths,json),paths,client,json);
    }
    private List<Mt5Candle> rates() {
        var rows=new ArrayList<Mt5Candle>();
        for (int date:new int[]{9,13,14,15,16}) for(int i=0;i<48;i++) {
            long time=LocalDate.of(2026,10,date).atTime(9,0).toEpochSecond(ZoneOffset.UTC)+i*300;
            rows.add(new Mt5Candle(time,100,110,90,105,10,20));
        }
        return rows;
    }
    @Test void progressiveM1HasSeparateVerifiedCacheAndKeepsOfficialM5() throws Exception {
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates());
        var source=new ArrayList<Mt5Candle>();
        for(var bar:rates()) if(Instant.ofEpochSecond(bar.time()).atOffset(ZoneOffset.UTC).toLocalDate().equals(day))
            for(int i=0;i<5;i++) source.add(new Mt5Candle(bar.time()+i*60,bar.open(),bar.high(),bar.low(),bar.close(),1,1));
        when(mt5.readCandles("WIN$N","M1",day,day)).thenReturn(source);
        var loader=loader(mt5);var official=loader.load("WIN$N",day,5);
        var updates=loader.progressive("WIN$N",day,official);
        assertThat(updates.stream().filter(c->c.closed()).toList()).isEqualTo(official);
        assertThat(loader(mt5).progressive("WIN$N",day,official)).isEqualTo(updates);
        verify(mt5,times(1)).readCandles("WIN$N","M1",day,day);
        assertThat(root.resolve("WIN$N/three-candles-m1/2026-10-16.json.metadata.json")).exists();
        assertThat(loader.load("WIN$N",day,5)).isEqualTo(official);
    }
    @Test void missingBoundaryMinuteDoesNotDiscardOtherNativePatternEvidenceOrRefetchIt() throws Exception {
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates());
        var source=new ArrayList<Mt5Candle>();
        long opening=day.atTime(9,0).toEpochSecond(ZoneOffset.UTC);
        for(var bar:rates())if(bar.time()>=opening)
            for(int i=0;i<5;i++)if(bar.time()+i*60!=opening+4*60)
                source.add(new Mt5Candle(bar.time()+i*60,bar.open(),bar.high(),bar.low(),bar.close(),1,1));
        when(mt5.readCandles("WIN$N","M1",day,day)).thenReturn(source);
        var loader=loader(mt5);var official=loader.load("WIN$N",day,5);
        // An official M5 close is preserved without pretending the absent final M1 exists.
        var progressive=loader.progressiveHistory("WIN$N",day,official);
        assertThat(progressive.missingM1Times()).containsExactly((opening+4*60)*1000);
        assertThat(progressive.updates().stream().filter(b->b.closed()).toList()).isEqualTo(official);
        assertThat(loader.patternMinutes("WIN$N",day,official)).containsExactlyElementsOf(source);
        assertThat(loader(mt5).patternMinutes("WIN$N",day,official)).containsExactlyElementsOf(source);
        verify(mt5,times(1)).readCandles("WIN$N","M1",day,day);
    }
    @Test void missingCacheFetchesM5OnceAndPersistsForAnotherLoaderWithoutLookahead() throws Exception {
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates());
        var loader=loader(mt5); assertThat(loader.ready("WIN$N",day)).isFalse();
        var bars=loader.load("WIN$N",day,5);
        var cached=loader(mt5).load("WIN$N",day,5);
        assertThat(cached).isEqualTo(bars);verify(mt5,times(1)).readCandles("WIN$N","M5",day.minusDays(28),day);
        long opening=day.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var plan=H1SessionPreparation.prepare("WIN$N",day,opening,bars);
        assertThat(plan.sufficient()).isTrue();
        assertThat(plan.sourceSessions()).containsExactly("2026-10-13","2026-10-14","2026-10-15");
        var replay=new ThreeCandleReplay("WIN$N",5,bars,opening,plan);
        assertThat(replay.candles()).hasSize(144);
        while (replay.status()!=ThreeCandleReplay.Status.COMPLETED) {
            assertThat(replay.candles()).allMatch(c->c.availableAtMsc()<=replay.availableAtMsc());
            assertThat(replay.pipeline().structure(6).levels()).allMatch(l->l.confirmedAtMsc()<=replay.availableAtMsc());
            replay.next();
        }
        verifyNoMoreInteractions(mt5);
    }
    @Test void invalidOhlcOrNoSelectedSessionIsNotSaved() throws Exception {
        when(mt5.readCandles(anyString(),eq("M5"),any(),any())).thenReturn(List.of(new Mt5Candle(day.atTime(9,0).toEpochSecond(ZoneOffset.UTC),100,99,90,105,0,0)));
        assertThatThrownBy(()->loader(mt5).load("WINZ26",day,5)).hasMessageContaining("OHLC");
        assertThat(loader(mt5).ready("WINZ26",day)).isFalse();
        when(mt5.readCandles(anyString(),eq("M5"),any(),any())).thenReturn(rates().stream().filter(c->c.time()<day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)).toList());
        assertThatThrownBy(()->loader(mt5).load("WINZ26",day,5)).hasMessageContaining("não retornou candles");
    }
    @Test void incompleteHistoryIsExplicitAndContractsNeverFallBackToContinuous() throws Exception {
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates());
        loader(mt5).load("WIN$N",day,5);
        when(mt5.readCandles("WINZ26","M5",day.minusDays(28),day)).thenThrow(new IOException("símbolo indisponível / terminal desconectado"));
        assertThatThrownBy(()->loader(mt5).load("WINZ26",day,5)).hasMessageContaining("WINZ26").hasMessageContaining("desconectado");
        doReturn(rates().subList(192,240)).when(mt5).readCandles("WINZ26","M5",day.minusDays(28),day);
        assertThatThrownBy(()->loader(mt5).load("WINZ26",day,5)).hasMessageContaining("insuficiente").hasMessageContaining("aquecimento H1");
        assertThat(loader(mt5).ready("WINZ26",day)).isFalse();
    }
    @Test void corruptedCacheIsRefetchedRatherThanUsed() throws Exception {
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates());
        var loader=loader(mt5);loader.load("WIN$N",day,5);
        Files.writeString(root.resolve("WIN$N/three-candles-m5/2026-10-16.json"),"corrupted");
        assertThat(loader.ready("WIN$N",day)).isFalse();loader.load("WIN$N",day,5);
        verify(mt5,times(2)).readCandles("WIN$N","M5",day.minusDays(28),day);
    }
    @Test void httpPreparationAndReplayUseOneFiniteQueryAndExposeUsefulErrors() throws Exception {
        when(mt5.readCandles("WINZ26","M5",day.minusDays(28),day)).thenReturn(rates());
        var paths=new ReplayHistoryProvider(root+"/{symbol}/{date}.ndjson","unused","unused");
        var local=new ThreeCandleHistory(paths,json);
        var controller=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController(
                local,mock(ThreeCandleLive.class),new Mt5Symbol("WINZ26"),new ThreeCandleHistoryLoader(local,paths,mt5,json));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var payload="{\"symbol\":\"WINZ26\",\"date\":\"2026-10-16\",\"startTime\":\"09:00\",\"timeframeMinutes\":5}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/three-candles/history/status")
                .param("symbol","WINZ26").param("date",day.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.cached").value(false));
        verifyNoInteractions(mt5);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/three-candles/history/prepare")
                .contentType("application/json").content(payload)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/three-candles/replays")
                .contentType("application/json").content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candles.length()").value(144)).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/three-candles/replays/"+id+"/commands")
                .contentType("application/json").content("{\"action\":\"NEXT\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candles.length()").value(145));
        verify(mt5,times(1)).readCandles("WINZ26","M5",day.minusDays(28),day);
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenThrow(new IOException("terminal desconectado"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/three-candles/history/prepare")
                .contentType("application/json").content(payload.replace("WINZ26","WIN$N")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail",org.hamcrest.Matchers.containsString("terminal desconectado")));
    }
    @Test @EnabledIfSystemProperty(named="threecandles.nativeM5",matches=".+")
    void actualMt5October16RatesRetainEveryOhlcAndTimestampAndCache() throws Exception {
        var nativeRates=Arrays.asList(json.readValue(Path.of(System.getProperty("threecandles.nativeM5")).toFile(),Mt5Candle[].class));
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(nativeRates);
        var bars=loader(mt5).load("WIN$N",day,5);
        assertThat(loader(mt5).load("WIN$N",day,5)).isEqualTo(bars);
        assertThat(bars).hasSize(nativeRates.size());
        for(int i=0;i<bars.size();i++) {
            var actual=bars.get(i).candle(); var expected=nativeRates.get(i);
            assertThat(actual.bucketStartTimeMsc()).isEqualTo(expected.time()*1000);
            assertThat(new double[]{actual.open(),actual.high(),actual.low(),actual.close()})
                    .containsExactly(expected.open(),expected.high(),expected.low(),expected.close());
        }
        var plan=H1SessionPreparation.prepare("WIN$N",day,day.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli(),bars);
        assertThat(plan.sourceSessions()).containsExactly("2026-10-13","2026-10-14","2026-10-15");
        assertThat(plan.sufficient()).isTrue(); assertThat(plan.levels()).isNotEmpty();
        String reference=System.getProperty("threecandles.nativeH1");
        if (reference!=null) {
            var hourly=json.readTree(Path.of(reference).toFile()).path("series").path("H1");
            var expected=new TreeMap<String,Long>();
            for(int i=2;i<hourly.size()-2;i++) {
                var c=hourly.get(i); long origin=c.path("time").asLong()*1000;
                if (!plan.sourceSessions().contains(LocalDate.ofEpochDay(origin/86400000L).toString())) continue;
                boolean top=true,bottom=true;
                for(int j=i-2;j<=i+2;j++) if(j!=i) {
                    top &= c.path("high").asDouble()>hourly.get(j).path("high").asDouble();
                    bottom &= c.path("low").asDouble()<hourly.get(j).path("low").asDouble();
                }
                long confirmed=hourly.get(i+2).path("time").asLong()*1000+3600000;
                if (confirmed>plan.sessionOpenMsc()) continue;
                if(top) expected.put("TOP:"+origin+":"+c.path("high").asDouble(),confirmed);
                if(bottom) expected.put("BOTTOM:"+origin+":"+c.path("low").asDouble(),confirmed);
            }
            var actual=new TreeMap<String,Long>();
            plan.candidates().forEach(l->actual.put(l.type()+":"+l.extremeTimeMsc()+":"+l.price(),l.confirmedAtMsc()));
            assertThat(actual).isEqualTo(expected);
            System.out.println("MT5_H1_OCT16 discrepancies=0 candidates="+actual.size());
        }
        String preview=System.getProperty("threecandles.preview16");
        if(preview!=null) {
            var replay=new ThreeCandleReplay("WIN$N",5,bars,plan.sessionOpenMsc(),plan);
            json.writeValue(Path.of(preview).toFile(),Map.of("id","oct16-preview","symbol","WIN$N","timeframeMinutes",5,
                    "status","PAUSED","speed",1,"availableAtMsc",replay.availableAtMsc(),"candles",replay.candles(),"structure",replay.pipeline().structure(6)));
        }
        verify(mt5,times(1)).readCandles("WIN$N","M5",day.minusDays(28),day);
        System.out.println("MT5_M5_OCT16 bars="+bars.size()+" previous="+plan.sourceSessions()+" H1="+plan.levels().size()+" cache=REUSED ohlcDifferences=0");
    }
}
