package br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j;

import br.com.bauzin.market.panic.panicscanner.domain.analysis.Candle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen output of the original application adapters, including rejection boundaries. */
class MovingAverageBaselineTest {
    @Test void applicationOutputsMatchBeforeExtraction() throws Exception {
        var rows = new ArrayList<String>();
        for (int n : new int[]{1,8,9,20,21,22,23,40}) {
            var daily = new ArrayList<Candle>(); var win = new ArrayList<WinCandle>();
            var mt5 = new ArrayList<Mt5Candle>();
            for (int i=0;i<n;i++) {
                var c=BigDecimal.valueOf(100 + i*0.37 + (i%3)*0.13);
                var d=LocalDate.of(2026,10,28).plusDays(i);
                // Cross midnight/session boundary while retaining the existing caller policy.
                var time=OffsetDateTime.parse("2026-10-28T23:00:00-03:00").plusMinutes(i*5L);
                daily.add(new Candle(d,c,c.add(BigDecimal.ONE),c.subtract(BigDecimal.ONE),c,BigDecimal.TEN));
                win.add(new WinCandle(time,c,c.add(BigDecimal.ONE),c.subtract(BigDecimal.ONE),c,BigDecimal.TEN));
                mt5.add(new Mt5Candle(time.toEpochSecond(),c.doubleValue(),c.doubleValue()+1,c.doubleValue()-1,c.doubleValue(),10,10));
            }
            try { rows.add("daily/"+n+"="+new Ta4jTechnicalAnalysisAdapter().analyze("TEST","1d",daily)); }
            catch (IllegalArgumentException e) { rows.add("daily/"+n+"="+e.getMessage()); }
            try { rows.add("win/"+n+"="+new Ta4jWinTechnicalAnalysisAdapter().analyze("5m",win)); }
            catch (IllegalArgumentException e) { rows.add("win/"+n+"="+e.getMessage()); }
            rows.add("mt5/"+n+"="+new Ta4jMt5Sma9Adapter().synchronize(mt5));
        }
        var fixture=Path.of("src/test/resources/indicators/moving-averages-before.txt");
        var actual=String.join("\n",rows)+"\n";
        if(Boolean.getBoolean("captureMovingAverageBaseline")) { Files.createDirectories(fixture.getParent()); Files.writeString(fixture,actual); }
        assertEquals(Files.readString(fixture),actual);
    }
}
