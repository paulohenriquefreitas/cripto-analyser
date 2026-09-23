package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import java.io.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mt5HistoricalPriceSourceTest {
    private static final String HEADER = """
            {"type":"header","symbol":"WINV26","startMsc":0,"endMsc":300000,"warmup":[],"official":[]}
            """;
    private String row(long time, double price, int flags) {
        return "{\"type\":\"tick\",\"timeMsc\":" + time + ",\"last\":" + price + ",\"flags\":" + flags + "}\n";
    }
    private String end(long rows) { return "{\"type\":\"end\",\"rows\":" + rows + "}\n"; }

    @Test void flagsSelectLastOnlyPreservingDuplicatesAndSameMillisecondOrder() throws Exception {
        String input = HEADER + row(1000, 1, 2) + row(1000, 2, 4) + row(1000, 3, 16)
                + row(1000, 188700, 8) + row(1000, 188705, 24) + row(1000, 188705, 24)
                + row(1000, 188710, 8 | 2 | 4 | 16 | 32) + end(7);
        var events = new ArrayList<CanonicalPriceEvent>();
        var stats = new Mt5HistoricalPriceSource().stream(new StringReader(input), h -> assertEquals("WINV26", h.symbol()), events::add);
        assertEquals(7, stats.eventsRead());
        assertEquals(4, stats.lastEvents());
        assertEquals(java.util.List.of(188700.0, 188705.0, 188705.0, 188710.0), events.stream().map(CanonicalPriceEvent::price).toList());
    }

    @Test void mapperLiveUsesLastRegardlessOfBidAskVolumeAndValidatesTime() {
        assertEquals(new CanonicalPriceEvent("WINV26", 1234, 100),
                Mt5CanonicalPriceMapper.liveSnapshot("WINV26", new Mt5Tick(1, 1234, 99, 101, 100, 123)));
        assertThrows(IllegalArgumentException.class, () -> Mt5CanonicalPriceMapper.liveSnapshot("WINV26", new Mt5Tick(2, 1234, 99, 101, 100)));
        assertThrows(IllegalArgumentException.class, () -> Mt5CanonicalPriceMapper.liveSnapshot("WINV26", new Mt5Tick(1, 1234, 99, 101, 0)));
        assertTrue(Mt5CanonicalPriceMapper.historical("WINV26", 1234, 0, 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Mt5CanonicalPriceMapper.historical("WINV26", 1234, 0, 8));
    }

    @Test void truncatedMalformedAndInvalidTrailerFailLoudly() {
        for (String input : new String[]{"", "null\n", "{}\n", HEADER, HEADER + row(1, 1, 8),
                HEADER + row(1, 1, 8) + end(0), HEADER + end(0) + row(1, 1, 8),
                HEADER + "{broken}\n", HEADER + "{\"type\":\"tick\",\"last\":1,\"flags\":8}\n",
                HEADER + "{\"type\":\"tick\",\"timeMsc\":1.5,\"last\":1,\"flags\":8}\n",
                HEADER + row(1, 1, -1), HEADER + row(1, 1, 8) + "{\"type\":\"end\"}\n"})
            assertThrows(IOException.class, () -> new Mt5HistoricalPriceSource().stream(new StringReader(input), h -> {}, e -> {}), input);
    }

    @Test void rawOrderAndHalfOpenRangeAreCheckedEvenForUnselectedQuotes() {
        for (String rows : new String[]{row(2000, 1, 2) + row(1999, 1, 8), row(-1, 1, 2), row(300000, 1, 8)})
            assertThrows(IOException.class, () -> new Mt5HistoricalPriceSource().stream(new StringReader(HEADER + rows), h -> {}, e -> {}));
    }

    @Test void consumerFailureIsPropagatedWithoutReadingAnotherEvent() {
        var failure = new IllegalStateException("consumer failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> new Mt5HistoricalPriceSource().stream(
                new StringReader(HEADER + row(1, 1, 8) + row(2, 2, 8) + end(2)), h -> {}, e -> { throw failure; })));
    }
}
