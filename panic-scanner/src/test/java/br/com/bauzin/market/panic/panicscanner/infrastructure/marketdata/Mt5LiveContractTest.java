package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mt5LiveContractTest {
    @Test void contractCheckpointCannotCrossSymbolsAndSameSymbolCanResume() {
        var seed=new Mt5TradeStreamClient.Cursor(1234,2);
        var old=new Mt5TradeStreamClient.Checkpoint("WINV26",seed);
        var fresh=new Mt5TradeStreamClient(new Mt5Symbol("WINZ26"),old,cursor -> { throw new AssertionError(); });
        assertNull(fresh.cursor());assertEquals("WINZ26",fresh.checkpoint().symbol());
        var resumed=new Mt5TradeStreamClient(new Mt5Symbol("WINV26"),old,cursor -> { throw new AssertionError(); });
        assertEquals(seed,resumed.cursor());
    }
    @Test void wrongContractIsRejectedBeforeDelivery() throws Exception {
        var cursor=new AtomicReference<Mt5TradeStreamClient.Cursor>();
        String binary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        try(var client=new Mt5TradeStreamClient(new Mt5Symbol("WINZ26"),null,seed -> {
            cursor.set(seed);
            return new ProcessBuilder(binary,"-cp",System.getProperty("java.class.path"),
                Mt5TradeStreamClientTest.Fixture.class.getName(),"cursor").start();
        })) {
            assertThrows(java.io.IOException.class,()->client.startWithCursor((trade,id)->fail("Foreign trade delivered"),
                ignored->{},(available,time)->{}));
            assertNull(cursor.get());assertNull(client.cursor());
        }
    }
    @Test void newApplicationContractGetsIndependentProfileAndCannotAcceptOldContract() {
        var old=new Mt5VolumeProfileService("09:00","18:00","",true,0,true,new Mt5Symbol("WINV26"));
        var next=new Mt5VolumeProfileService("09:00","18:00","",true,0,true,new Mt5Symbol("WINZ26"));
        long time=LocalDate.of(2026,10,20).atTime(10,0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli();
        var id=new Mt5TradeStreamClient.Cursor(time,1);
        old.onTrade(new MarketTrade("WINV26",time,100,10,AggressorSide.BUY),id);
        assertNull(next.snapshot().profile());
        next.onTrade(new MarketTrade("WINZ26",time,100,2,AggressorSide.SELL),id);
        assertEquals(2,next.snapshot().profile().totalVolume().doubleValue());
        assertEquals(10,old.snapshot().profile().totalVolume().doubleValue());
        next.onTrade(new MarketTrade("WINV26",time+1,100,20,AggressorSide.BUY),new Mt5TradeStreamClient.Cursor(time+1,1));
        assertEquals(2,next.snapshot().profile().totalVolume().doubleValue());
        assertTrue(next.snapshot().profile().faults().contains("SOURCE_SYMBOL_MISMATCH"));
    }
    @Test void singleDefaultAndProcessEnvironmentAreShared() {
        assertEquals("WINZ26",Mt5Symbol.defaults().value());
        var builder=new ProcessBuilder("python");new Mt5Symbol("winz26").configure(builder);
        assertEquals("WINZ26",builder.environment().get("MT5_SYMBOL"));
        assertThrows(IllegalArgumentException.class,()->new Mt5Symbol("WIN$N"));
    }
    @Test void springSelectionAndBrowserContractPinningUseTheSameSetting() throws Exception {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(Mt5Symbol.class).withPropertyValues("mt5.symbol=WINZ26")
            .run(context -> assertEquals("WINZ26",context.getBean(Mt5Symbol.class).value()));
        var symbol=new Mt5Symbol("WINZ26");
        var useCase=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase.class);
        var ticks=org.mockito.Mockito.mock(br.com.bauzin.market.panic.panicscanner.api.Mt5TickWebSocketHandler.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new br.com.bauzin.market.panic.panicscanner.api.Mt5LiveConfigController(symbol),
            new br.com.bauzin.market.panic.panicscanner.api.Mt5CandlesController(useCase,ticks,symbol)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mt5/config"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.symbol").value("WINZ26"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mt5/candles").param("symbol","WINV26"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        org.mockito.Mockito.verifyNoInteractions(useCase,ticks);
    }
}
