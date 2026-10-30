package br.com.bauzin.market.panic.panicscanner.strategy.threecandles;

import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.api.ThreeCandlesController;
import br.com.bauzin.market.panic.panicscanner.application.win.replay.ReplayHistoryProvider;
import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.application.Mt5ChartCandle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import java.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ThreeCandleAdaptersTest {
    final ObjectMapper json=new ObjectMapper();
    @TempDir Path temp;
    private ReplayHistoryProvider provider() {
        return new ReplayHistoryProvider(temp+"/{symbol}/{date}.ndjson","unused-python","unused-script");
    }
    private void fixture(ReplayHistoryProvider p) throws Exception {
        Path path=p.resolve("WINZ26",LocalDate.of(2026,10,23)); Files.createDirectories(path.getParent());
        long start=LocalDate.of(2026,10,23).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var candles=new ArrayList<Mt5Candle>();
        for(int i=0;i<6;i++) candles.add(new Mt5Candle((start+12*3600000L+i*300000)/1000,100,110,90,100+i,0,0));
        var node=json.valueToTree(new Mt5HistoricalPriceSource.Header("WINZ26",start,start+86400000,List.of(),candles,2));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put("type","header");
        Files.writeString(path,json.writeValueAsString(node)+"\n{\"type\":\"end\",\"rows\":0}\n");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        json.writeValue(path.resolveSibling(path.getFileName()+".metadata.json").toFile(),
                new ReplayHistoryProvider.Metadata("2026-10-23","WINZ26","MT5",0,0,null,null,"2026-10-24T00:00:00Z",2,"COMPLETE",sha));
    }
    @Test void localHistoryValidatesHashAndNeverRunsExporter() throws Exception {
        var p=provider(); fixture(p); var history=new ThreeCandleHistory(p,json);
        assertThat(history.sessions()).hasSize(1);
        assertThat(history.load("WINZ26",LocalDate.of(2026,10,23),5)).hasSize(6);
        assertThat(history.load("WINZ26",LocalDate.of(2026,10,23),15)).hasSize(2);
        Files.writeString(p.resolve("WINZ26",LocalDate.of(2026,10,23)),"corrupted");
        assertThatThrownBy(() -> history.load("WINZ26",LocalDate.of(2026,10,23),5)).hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> history.load("WINZ26",LocalDate.of(2026,10,22),5)).hasMessageContaining("404");
    }
    @Test void endpointsRevealOnlyAvailableBarsAndCommandsOperateWithoutTheChart() throws Exception {
        var p=provider(); fixture(p);
        var controller=new ThreeCandlesController(new ThreeCandleHistory(p,json),mock(ThreeCandleLive.class),new Mt5Symbol("WINZ26"));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var response=mvc.perform(post("/api/three-candles/replays").contentType("application/json")
                .content("{\"symbol\":\"WINZ26\",\"date\":\"2026-10-23\",\"startTime\":\"09:00\",\"timeframeMinutes\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candles.length()").value(0)).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/three-candles/replays/"+id+"/commands").contentType("application/json").content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candles.length()").value(1)).andExpect(jsonPath("$.candles[0].closed").value(true));
        mvc.perform(post("/api/three-candles/replays/"+id+"/commands").contentType("application/json").content("{\"action\":\"RESET\"}"))
                .andExpect(jsonPath("$.candles.length()").value(0));
        mvc.perform(delete("/api/three-candles/replays/"+id)).andExpect(status().isOk());
        mvc.perform(get("/api/three-candles/replays/"+id)).andExpect(status().isNotFound());
    }
    @Test void structuralWireContractHonorsConfirmationLimitAndReset() throws Exception {
        var p=provider(); fixture(p); Path file=p.resolve("WINZ26",LocalDate.of(2026,10,23));
        String text=Files.readString(file); int end=text.indexOf('\n');
        var header=json.readTree(text.substring(0,end));
        ((com.fasterxml.jackson.databind.node.ObjectNode)header.path("official").get(2)).put("high",120).put("low",80);
        Files.writeString(file,json.writeValueAsString(header)+text.substring(end));
        Path manifest=file.resolveSibling(file.getFileName()+".metadata.json");
        var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(manifest.toFile());
        metadata.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        json.writeValue(manifest.toFile(),metadata);
        var mvc=MockMvcBuilders.standaloneSetup(new ThreeCandlesController(new ThreeCandleHistory(p,json),
                mock(ThreeCandleLive.class),new Mt5Symbol("WINZ26"))).build();
        var response=mvc.perform(post("/api/three-candles/replays").contentType("application/json")
                .content("{\"symbol\":\"WINZ26\",\"date\":\"2026-10-23\",\"startTime\":\"12:20\",\"timeframeMinutes\":5,\"timeBasis\":\"MT5_WALL_CLOCK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.structure.levels.length()").value(0))
                .andExpect(jsonPath("$.dow.symbol").value("WINZ26"))
                .andExpect(jsonPath("$.dow.pivots.length()").value(0)).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/three-candles/replays/"+id+"/commands?maxLevels=1").contentType("application/json").content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.structure.levels.length()").value(2))
                .andExpect(jsonPath("$.structure.totalActive").value(2)).andExpect(jsonPath("$.structure.levels[0].relevance").value("PROXIMITY"))
                .andExpect(jsonPath("$.structure.levels[0].region.midpoint").value(100))
                .andExpect(jsonPath("$.dow.pivots.length()").value(2))
                .andExpect(jsonPath("$.dow.movements.length()").value(0))
                .andExpect(jsonPath("$.dow.state").value("UNDEFINED"));
        mvc.perform(get("/api/three-candles/replays/"+id+"?maxLevels=6"))
                .andExpect(jsonPath("$.structure.levels.length()").value(2));
        mvc.perform(post("/api/three-candles/replays/"+id+"/commands").contentType("application/json").content("{\"action\":\"RESET\"}"))
                .andExpect(jsonPath("$.structure.levels.length()").value(0))
                .andExpect(jsonPath("$.dow.pivots.length()").value(0))
                .andExpect(jsonPath("$.dow.events.length()").value(0));
    }

    private Mt5ChartCandle source(long seconds,double close) {
        return new Mt5ChartCandle(new Mt5Candle(seconds,100,110,90,close,0,0),null,null,null,null);
    }
    @Test void liveAndReplayStructureAgreeAndReconnectRebuildsWithoutDuplicates() throws Exception {
        var reader=mock(GetMt5CandlesUseCase.class); var handler=mock(Mt5TickWebSocketHandler.class);
        double[] highs={105,110,120,110,105,110}; double[] lows={95,90,80,90,95,90};
        var rows=new ArrayList<Mt5ChartCandle>();
        for(int i=0;i<6;i++) rows.add(new Mt5ChartCandle(new Mt5Candle(i*300L,100,highs[i],lows[i],100,0,0),null,null,null,null));
        when(reader.execute()).thenReturn(rows);
        var shared=Optional.of(new Mt5TickWebSocketHandler.SharedCandle(new Mt5Tick(1501,1501000,100,100,100),
                new IntrabarCandleSnapshot("WINZ26",1500000,100,110,90,100)));
        when(handler.latestAvailableCandle()).thenReturn(shared);
        var live=new ThreeCandleLive(reader,handler,new Mt5Symbol("WINZ26"),null,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var first=live.snapshot("WINZ26",5);
        assertThat(first.structure().levels()).hasSize(2);
        var replay=new br.com.bauzin.market.panic.panicscanner.strategy.threecandles.replay.ThreeCandleReplay(
                "WINZ26",5,first.candles().stream().filter(c -> c.closed()).toList(),1500000,first.structure().preparation());
        assertThat(first.structure()).isEqualTo(replay.pipeline().structure(6));
        when(handler.latestAvailableCandle()).thenReturn(Optional.empty());
        assertThat(live.snapshot("WINZ26",5).structure()).isEqualTo(first.structure());
        when(handler.latestAvailableCandle()).thenReturn(shared);
        assertThat(live.snapshot("WINZ26",5).structure()).isEqualTo(first.structure());
        when(handler.latestAvailableCandle()).thenReturn(Optional.empty());
        live.snapshot("WINZ26",5);
        when(reader.execute()).thenReturn(rows.subList(3,6));
        when(handler.latestAvailableCandle()).thenReturn(shared);
        assertThat(live.snapshot("WINZ26",5).structure()).isEqualTo(first.structure());
        var limited=live.snapshot("WINZ26",5,1);
        assertThat(limited.structure().levels()).hasSize(2); assertThat(limited.structure().totalActive()).isEqualTo(2);
        verify(reader,times(3)).execute();
    }

    @Test void liveKeepsPreviousSessionsVisibleThroughUpdatesRolloverAndReconnect() throws Exception {
        var reader=mock(GetMt5CandlesUseCase.class);
        var handler=mock(Mt5TickWebSocketHandler.class);
        var warmup=mock(Mt5ProcessClient.class);
        var history=new ArrayList<Mt5Candle>();
        var days=List.of("2026-10-21","2026-10-22","2026-10-23","2026-10-26","2026-10-27","2026-10-28","2026-10-29");
        for(var day:days) {
            long start=LocalDate.parse(day).atTime(9,0).toEpochSecond(ZoneOffset.UTC);
            for(int i=0;i<3;i++) history.add(new Mt5Candle(start+i*300,100,110,90,100,0,0));
        }
        long current=history.getLast().time();
        var recent=history.subList(history.size()-2,history.size()).stream()
            .map(c->new Mt5ChartCandle(c,null,null,null,null)).toList();
        when(reader.execute()).thenReturn(recent);
        when(warmup.readCandles()).thenReturn(history);
        var tick=new Mt5Tick(current+60,(current+60)*1000,100,102,102);
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(tick,
            new IntrabarCandleSnapshot("WINZ26",current*1000,100,115,90,102))));
        var clock=Clock.fixed(Instant.parse("2026-10-29T12:11:00Z"),ZoneId.of("America/Sao_Paulo"));
        var live=new ThreeCandleLive(reader,handler,new Mt5Symbol("WINZ26"),warmup,clock);
        var first=live.snapshot("WINZ26",5);
        assertThat(first.candles()).hasSize(history.size());
        assertThat(first.candles().stream().map(c->LocalDate.ofEpochDay(c.candle().bucketStartTimeMsc()/86400000L)).distinct().toList())
            .containsExactlyElementsOf(days.stream().map(LocalDate::parse).toList());
        assertThat(first.candles().getLast().closed()).isFalse();
        assertThat(first.candles().getLast().candle().close()).isEqualTo(102);
        assertThat(live.snapshot("WINZ26",5).candles()).isEqualTo(first.candles());
        verify(warmup,times(1)).readCandles();
        // Closing the forming bar and opening the next one must keep the historical prefix.
        var rolled=new ArrayList<>(recent);
        rolled.set(rolled.size()-1,new Mt5ChartCandle(new Mt5Candle(current,100,115,90,102,0,0),null,null,null,null));
        rolled.add(source(current+300,103));
        when(reader.execute()).thenReturn(rolled);
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(
            new Mt5Tick(current+301,(current+301)*1000,100,103,103),
            new IntrabarCandleSnapshot("WINZ26",(current+300)*1000,100,110,90,103))));
        var next=live.snapshot("WINZ26",5);
        assertThat(next.candles()).hasSize(history.size()+1);
        assertThat(next.candles().subList(0,history.size()-1)).isEqualTo(first.candles().subList(0,history.size()-1));
        assertThat(next.candles().get(next.candles().size()-2).closed()).isTrue();
        when(handler.latestAvailableCandle()).thenReturn(Optional.empty());
        assertThat(live.snapshot("WINZ26",5).candles()).isEqualTo(next.candles());
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(
            new Mt5Tick(current+302,(current+302)*1000,100,104,104),
            new IntrabarCandleSnapshot("WINZ26",(current+300)*1000,100,110,90,104))));
        var recovered=live.snapshot("WINZ26",5);
        assertThat(recovered.candles()).hasSize(next.candles().size());
        assertThat(recovered.candles().getFirst()).isEqualTo(first.candles().getFirst());
        assertThat(recovered.candles().getLast().candle().close()).isEqualTo(104);
        verify(warmup,times(2)).readCandles();
    }

    @Test void liveReusesSharedOhlcUpdatesAndReconcilesRolloverAndReconnect() throws Exception {
        var reader=mock(GetMt5CandlesUseCase.class); var handler=mock(Mt5TickWebSocketHandler.class);
        when(reader.execute()).thenReturn(List.of(source(0,100),source(300,101)));
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(
                new Mt5Tick(301,301000,100,102,102),new IntrabarCandleSnapshot("WINZ26",300000,100,115,90,102))));
        var live=new ThreeCandleLive(reader,handler,new Mt5Symbol("WINZ26"),null,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var first=live.snapshot("WINZ26",5); assertThat(first.status()).isEqualTo("LIVE");
        assertThat(first.candles().getLast().candle().high()).isEqualTo(115); // Not inferred from the last sampled tick.
        assertThat(first.candles().getFirst().closed()).isTrue(); assertThat(first.candles().getLast().closed()).isFalse();
        assertThat(live.snapshot("WINZ26",5).candles()).hasSize(2); verify(reader,times(1)).execute();
        when(reader.execute()).thenReturn(List.of(source(0,100),source(300,102),source(600,103)));
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(
                new Mt5Tick(601,601000,100,103,103),new IntrabarCandleSnapshot("WINZ26",600000,100,110,90,103))));
        var next=live.snapshot("WINZ26",5); assertThat(next.candles()).hasSize(3); assertThat(next.candles().get(1).closed()).isTrue();
        when(handler.latestAvailableCandle()).thenReturn(Optional.empty());
        assertThat(live.snapshot("WINZ26",5).status()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> live.snapshot("WINV26",5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(live.snapshot("WINZ26",15).timeframeMinutes()).isEqualTo(15);
        when(handler.latestAvailableCandle()).thenReturn(Optional.of(new Mt5TickWebSocketHandler.SharedCandle(
                new Mt5Tick(602,602000,100,104,104),new IntrabarCandleSnapshot("WINZ26",600000,100,110,90,104))));
        var recovered=live.snapshot("WINZ26",5);
        assertThat(recovered.status()).isEqualTo("LIVE");
        assertThat(recovered.candles()).hasSize(3);
        assertThat(recovered.candles().getLast().candle().close()).isEqualTo(104);
        verify(reader,times(3)).execute();
    }
}
