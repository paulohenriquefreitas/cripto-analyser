package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.AggressorSide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class Mt5AggressorMapperTest {
    @ParameterizedTest
    @CsvSource({"56,BUY","88,SELL","1080,BUY","1112,SELL","1144,AMBIGUOUS","1336,BUY","1368,SELL",
                "120,AMBIGUOUS","24,AMBIGUOUS","32,AMBIGUOUS","1026,AMBIGUOUS","-1,AMBIGUOUS",
                "184,AMBIGUOUS","312,AMBIGUOUS","2104,AMBIGUOUS","3128,AMBIGUOUS"})
    void officialDirectionAndVerifiedExtraMasksAgreeWithPython(int flags, AggressorSide expected) throws Exception {
        assertEquals(expected,Mt5AggressorMapper.side(flags));
        String message="{\"type\":\"trade\",\"symbol\":\"WINZ26\",\"timeMsc\":1000,\"price\":100,\"volume\":2,"
                +"\"side\":\""+expected+"\",\"flags\":"+flags+",\"volumeSource\":\"MT5_VOLUME_REAL\",\"cursorOrdinal\":1}";
        assertEquals(expected,Mt5TradeStreamClient.parseLine(message).side());
    }
}
