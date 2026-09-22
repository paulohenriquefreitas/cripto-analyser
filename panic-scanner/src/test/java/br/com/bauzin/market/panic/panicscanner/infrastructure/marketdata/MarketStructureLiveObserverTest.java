package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureConfig;
import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketStructureLiveObserverTest {
    @Test
    void feedsTickTimestampPriceAndCanonicalIntrabarAveragesIntoEngine() {
        MarketStructureEngine engine = new MarketStructureEngine(MarketStructureConfig.defaults());
        MarketStructureLiveObserver observer = new MarketStructureLiveObserver(engine, false);
        double[] distances = {50, 35, 20, 8, 2, -5, 4, 15, 25};
        for (int i = 0; i < distances.length; i++) {
            double price = 188_700 + distances[i];
            observer.onTick(new Mt5Tick(i, i, price - 5, price + 5, price, 1),
                    new Ta4jMt5Sma9Adapter.Current(0, 188_710, 188_700.0));
        }

        var completed = engine.lastCompleted(PriceReference.SMA21).orElseThrow();
        assertEquals(25, completed.exitDistance());
        assertEquals(5, completed.maxPenetrationPoints());
        assertTrue(completed.touched());
        assertTrue(completed.crossed());
    }

    @Test
    void waitsUntilBothCanonicalAveragesAreAvailable() {
        MarketStructureEngine engine = new MarketStructureEngine();
        MarketStructureLiveObserver observer = new MarketStructureLiveObserver(engine, false);
        observer.onTick(new Mt5Tick(1, 1, 100, 110, 105, 1), null);
        observer.onTick(new Mt5Tick(2, 2, 100, 110, 105, 1),
                new Ta4jMt5Sma9Adapter.Current(0, 100, null));
        assertTrue(engine.snapshot(PriceReference.SMA9).isEmpty());
    }
}
