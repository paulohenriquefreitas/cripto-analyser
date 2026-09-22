package br.com.bauzin.market.panic.panicscanner.api;

import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;

import br.com.bauzin.market.panic.panicscanner.application.usecase.GetMt5CandlesUseCase;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5ProcessClient;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class Mt5CandlesControllerTest {
    private MockMvc mvc(Mt5ProcessClient client) {
        var sma = new Ta4jMt5Sma9Adapter();
        var handler = new Mt5TickWebSocketHandler(new com.fasterxml.jackson.databind.ObjectMapper(), sma);
        return MockMvcBuilders.standaloneSetup(
                new Mt5CandlesController(new GetMt5CandlesUseCase(client, sma), handler)).build();
    }

    @Test
    void returnsLatestHundredWithoutChangingTimestampOrOhlc() throws Exception {
        List<Mt5Candle> data = IntStream.range(0, 1000)
                .mapToObj(i -> new Mt5Candle(1789991700L + i * 300L, 10, 12, 9, 11, 12345, 229673))
                .toList();
        mvc(new Mt5ProcessClient() {
            @Override public List<Mt5Candle> readCandles() { return data; }
        }).perform(get("/api/mt5/candles"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].time").value(data.get(900).time()))
                .andExpect(jsonPath("$[99].time").value(data.getLast().time()))
                .andExpect(jsonPath("$[99].open").value(10))
                .andExpect(jsonPath("$[99].high").value(12))
                .andExpect(jsonPath("$[99].low").value(9))
                .andExpect(jsonPath("$[99].close").value(11))
                .andExpect(jsonPath("$[99].tickVolume").value(12345))
                .andExpect(jsonPath("$[99].realVolume").value(229673))
                .andExpect(jsonPath("$[0].sma9").value(11))
                .andExpect(jsonPath("$[99].sma9").value(11))
                .andExpect(jsonPath("$[0].sma21").value(11))
                .andExpect(jsonPath("$[99].sma21").value(11));
    }

    @Test
    void returnsEmptyArrayWhenClientReturnsNoData() throws Exception {
        mvc(new Mt5ProcessClient() {
            @Override public List<Mt5Candle> readCandles() { return List.of(); }
        }).perform(get("/api/mt5/candles"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void reportsIntegrationFailureWithoutLeakingProcessDetails() throws Exception {
        mvc(new Mt5ProcessClient() {
            @Override public List<Mt5Candle> readCandles() throws IOException {
                throw new IOException("Python failed: diagnostic details");
            }
        }).perform(get("/api/mt5/candles"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void preservesInterruptAndReturnsUnavailable() throws Exception {
        try {
            mvc(new Mt5ProcessClient() {
                @Override public List<Mt5Candle> readCandles() throws InterruptedException {
                    throw new InterruptedException("test");
                }
            }).perform(get("/api/mt5/candles")).andExpect(status().isServiceUnavailable());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
