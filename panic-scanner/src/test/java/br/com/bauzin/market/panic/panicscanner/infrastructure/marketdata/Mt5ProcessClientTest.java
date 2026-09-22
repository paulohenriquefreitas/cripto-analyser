package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class Mt5ProcessClientTest {
    private static final String CANDLE = """
            {"time":1789582800,"open":187500.0,"high":187700.0,"low":187450.0,
             "close":187640.0,"tickVolume":12345,"realVolume":52341}
            """;

    @Test
    void deserializesAllFieldsAndPreservesTimestamp() throws IOException {
        var candles = Mt5ProcessClient.parseResponse(0, "[" + CANDLE + "]", "");
        assertEquals(1, candles.size());
        assertEquals(new Mt5Candle(1789582800L, 187500, 187700, 187450, 187640, 12345, 52341),
                candles.getFirst());
    }

    @Test
    void acceptsFlatCandlesAndTimeGaps() throws IOException {
        String flat = """
                {"time":1789584000,"open":10,"high":10,"low":10,"close":10,
                 "tickVolume":0,"realVolume":0}
                """;
        assertEquals(2, Mt5ProcessClient.parseResponse(0, "[" + CANDLE + "," + flat + "]", "").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "null", "[]", "[null]", "not json", "{}", "[{}]", "[] trailing"})
    void rejectsEmptyOrInvalidResponses(String response) {
        assertThrows(IOException.class, () -> Mt5ProcessClient.parseResponse(0, response, ""));
    }

    @Test
    void rejectsNullOutput() {
        assertThrows(IOException.class, () -> Mt5ProcessClient.parseResponse(0, null, ""));
    }

    @Test
    void reportsExitCodeAndStderrBeforeParsing() {
        var error = assertThrows(IOException.class,
                () -> Mt5ProcessClient.parseResponse(7, "not JSON", "mt5.initialize() falhou"));
        assertTrue(error.getMessage().contains("exit code 7"));
        assertTrue(error.getMessage().contains("mt5.initialize() falhou"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsDuplicateAndDescendingTimestamps() {
        for (String second : new String[] {CANDLE, CANDLE.replace("1789582800", "1789582500")}) {
            assertThrows(IOException.class,
                    () -> Mt5ProcessClient.parseResponse(0, "[" + CANDLE + "," + second + "]", ""));
        }
    }

    @Test
    void rejectsInvalidPricesAndMissingOrNullFields() {
        for (String invalid : new String[] {
                CANDLE.replace("187700.0", "1"), CANDLE.replace("187500.0", "0"),
                CANDLE.replace("187640.0", "-1"), CANDLE.replace("187500.0", "null"),
                CANDLE.replace("187700.0", "1e999"), CANDLE.replace("\"time\":1789582800,", "")}) {
            assertThrows(IOException.class,
                    () -> Mt5ProcessClient.parseResponse(0, "[" + invalid + "]", ""));
        }
    }

    @Test
    void rejectsTrailingJsonAfterValidCandles() {
        assertThrows(IOException.class,
                () -> Mt5ProcessClient.parseResponse(0, "[" + CANDLE + "] []", ""));
    }
}
