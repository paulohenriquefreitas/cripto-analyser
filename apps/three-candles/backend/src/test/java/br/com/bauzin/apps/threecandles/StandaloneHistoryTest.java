package br.com.bauzin.apps.threecandles;

import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.*;
import br.com.bauzin.market.panic.panicscanner.strategy.threecandles.infrastructure.ThreeCandleHistoryLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="market.capture.enabled=false")
class StandaloneHistoryTest {
    static final Path ROOT=Path.of("target","standalone-history-"+UUID.randomUUID());
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry) {
        registry.add("panic-scanner.replay.history-file",() -> ROOT+"/{symbol}/{date}.ndjson");
    }
    @MockitoBean Mt5ProcessClient mt5;
    @Autowired ThreeCandleHistoryLoader history;

    @Test void fetchesAndStoresHistoryDirectlyWithoutWinBackendOrToken() throws Exception {
        var day=LocalDate.of(2026,10,16);
        var rates=new ArrayList<Mt5Candle>();
        for(int date:new int[]{9,13,14,15,16}) for(int i=0;i<48;i++) {
            long time=LocalDate.of(2026,10,date).atTime(9,0).toEpochSecond(ZoneOffset.UTC)+i*300;
            rates.add(new Mt5Candle(time,100,110,90,105,10,20));
        }
        when(mt5.readCandles("WIN$N","M5",day.minusDays(28),day)).thenReturn(rates);
        var bars=history.load("WIN$N",day,5);
        assertThat(bars).hasSize(rates.size());
        for(int i=0;i<rates.size();i++) {
            assertThat(bars.get(i).candle().bucketStartTimeMsc()).isEqualTo(rates.get(i).time()*1000);
            assertThat(bars.get(i).candle().close()).isEqualTo(rates.get(i).close());
        }
        assertThat(history.load("WIN$N",day,5)).isEqualTo(bars);
        verify(mt5,times(1)).readCandles("WIN$N","M5",day.minusDays(28),day);
        assertThat(ROOT.resolve("WIN$N/three-candles-m5/2026-10-16.json.metadata.json")).exists();
    }
}
