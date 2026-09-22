package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MarketStructureSample;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;
import br.com.bauzin.market.panic.panicscanner.infrastructure.ta4j.Ta4jMt5Sma9Adapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Feeds normalized intrabar price/SMA values to the pure structure engine. */
@Component
public class MarketStructureLiveObserver {
    private static final Logger LOG = LoggerFactory.getLogger(MarketStructureLiveObserver.class);
    private final MarketStructureEngine engine;
    private final boolean logging;

    public MarketStructureLiveObserver() {
        this(new MarketStructureEngine(), true);
    }

    MarketStructureLiveObserver(MarketStructureEngine engine, boolean logging) {
        this.engine = engine;
        this.logging = logging;
    }

    public synchronized void onTick(Mt5Tick tick, Ta4jMt5Sma9Adapter.Current current) {
        if (current == null || current.sma21() == null) return;
        var update = engine.onSample(new MarketStructureSample(
                "WINV26", tick.timeMsc(), tick.last(), current.value(), current.sma21()));
        if (!logging) return;
        update.events().forEach(this::logEvent);
        update.completedInteractions().forEach(this::logCompleted);
    }

    public synchronized void reset() {
        engine.reset();
    }

    MarketStructureEngine engine() {
        return engine;
    }

    private void logEvent(MarketStructureEvent event) {
        LOG.info("[STRUCTURE] WINV26 {} {} timeMsc={} price={} average={} distance={} side={}",
                event.reference(), event.type(), event.timeMsc(), event.price(),
                event.movingAverage(), event.distance(), event.side());
    }

    private void logCompleted(MovingAverageInteraction value) {
        LOG.info("[STRUCTURE] WINV26 {} COMPLETED approachSide={} exitSide={} touched={} crossed={} "
                        + "maxPenetration={} minimumDistance={} durationMsc={} timeNearMsc={}",
                value.reference(), value.approachSide(), value.exitSide(), value.touched(), value.crossed(),
                value.maxPenetrationPoints(), value.minimumAbsoluteDistance(), value.durationMsc(),
                value.timeNearAverageMsc());
    }
}
