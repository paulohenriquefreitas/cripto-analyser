package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5Candle;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5HistoricalPriceSource;
import br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.HistoricalCanonicalPriceEvent;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public final class M5ReplaySession {
    public enum Status { READY, PLAYING, PAUSED, COMPLETED, ERROR, CLOSED }
    public record Snapshot(String type, String replayId, long timeMsc, Status status,
                           Candle candle, Double sma9, Double sma21, Double vwap, String vwapSession) {}
    public record Candle(long time, double open, double high, double low, double close, long realVolume) {}
    public record RuleOccurrenceMessage(
            String type,
            String occurrenceId,
            String ruleId,
            String symbol,
            long timeMsc,
            double price,
            RuleDirection direction,
            PriceReference reference,
            PriceSide approachSide,
            PriceSide exitSide) {}
    public record SetupEventMessage(
            String type,
            String eventId,
            String setupType,
            String symbol,
            long timeMsc,
            long candleTimeMsc,
            double price) {}
    public record Metrics(long eventsProcessed, long marketStatesPublished, long coalescedMarketStates) {}

    private static final long DEFAULT_VISUAL_INTERVAL_NANOS = 50_000_000L;

    private final String replayId = UUID.randomUUID().toString();
    private final Path historyFile;
    private final String requestedSymbol;
    private final Mt5HistoricalPriceSource source;
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<RuleOccurrenceMessage>> ruleOccurrenceListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SetupEventMessage>> setupEventListeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final long visualIntervalNanos;
    private final LongAdder eventsProcessed = new LongAdder();
    private final LongAdder marketStatesPublished = new LongAdder();
    private final LongAdder coalescedMarketStates = new LongAdder();
    private volatile Status status = Status.READY;
    private volatile long currentTimeMsc = -1;
    private volatile Snapshot latestMarketState;
    private volatile Snapshot lastPublishedMarketState;
    private volatile long lastVisualPublishNanos;
    private volatile long lastVisualBucket = Long.MIN_VALUE;
    private Thread worker;

    public M5ReplaySession(Path historyFile, String requestedSymbol) {
        this(historyFile, requestedSymbol, DEFAULT_VISUAL_INTERVAL_NANOS);
    }

    M5ReplaySession(Path historyFile, String requestedSymbol, long visualIntervalNanos) {
        this.historyFile = Objects.requireNonNull(historyFile, "historyFile");
        this.requestedSymbol = Objects.requireNonNull(requestedSymbol, "requestedSymbol");
        this.visualIntervalNanos = visualIntervalNanos;
        this.source = new Mt5HistoricalPriceSource();
    }

    public String replayId() { return replayId; }
    public Status status() { return status; }
    public long currentTimeMsc() { return currentTimeMsc; }
    public Metrics metrics() {
        return new Metrics(eventsProcessed.sum(), marketStatesPublished.sum(), coalescedMarketStates.sum());
    }
    public void addListener(Consumer<Snapshot> listener) { listeners.add(listener); }
    public void removeListener(Consumer<Snapshot> listener) { listeners.remove(listener); }
    public void addRuleOccurrenceListener(Consumer<RuleOccurrenceMessage> listener) {
        ruleOccurrenceListeners.add(listener);
    }
    public void removeRuleOccurrenceListener(Consumer<RuleOccurrenceMessage> listener) {
        ruleOccurrenceListeners.remove(listener);
    }
    public void addSetupEventListener(Consumer<SetupEventMessage> listener) {
        setupEventListeners.add(listener);
    }
    public void removeSetupEventListener(Consumer<SetupEventMessage> listener) {
        setupEventListeners.remove(listener);
    }

    public void play() {
        synchronized (lock) {
            if (status == Status.COMPLETED || status == Status.CLOSED) return;
            status = Status.PLAYING;
            lock.notifyAll();
            if (started.compareAndSet(false, true)) {
                worker = Thread.ofVirtual().name("replay-" + replayId).start(this::run);
            }
        }
        publish(new Snapshot("CLOCK", replayId, currentTimeMsc, status, null, null, null, null, null));
    }

    public void pause() {
        synchronized (lock) {
            if (status == Status.PLAYING) status = Status.PAUSED;
        }
        flushLatestMarketState(Status.PAUSED);
        publish(new Snapshot("CLOCK", replayId, currentTimeMsc, status, null, null, null, null, null));
    }

    public void close() {
        synchronized (lock) {
            status = Status.CLOSED;
            lock.notifyAll();
        }
        if (worker != null) worker.interrupt();
    }

    private void run() {
        try (var reader = Files.newBufferedReader(historyFile)) {
            IntrabarM5Processor processor = new IntrabarM5Processor();
            MarketStructureEngine structureEngine = new MarketStructureEngine();
            CausalSessionVwap vwap = new CausalSessionVwap();
            Pullback09Setup pullback09 = new Pullback09Setup();
            long[] occurrenceNumber = {0};
            source.streamHistorical(reader, header -> {
                if (!requestedSymbol.equalsIgnoreCase(header.symbol())) {
                    throw new IllegalArgumentException("History symbol differs from requested symbol");
                }
                processor.warmUp(header.symbol(), header.startMsc(), toSnapshots(header.symbol(), header.warmup()));
                vwap.warmUp(header.symbol(), header.warmup());
                structureEngine.reset();
            }, event -> accept(processor, structureEngine, vwap, pullback09, occurrenceNumber, event));
            synchronized (lock) {
                if (status != Status.CLOSED) status = Status.COMPLETED;
            }
            flushLatestMarketState(Status.COMPLETED);
            publish(new Snapshot("COMPLETED", replayId, currentTimeMsc, status, null, null, null, null, null));
        } catch (Exception ex) {
            synchronized (lock) {
                if (status != Status.CLOSED) status = Status.ERROR;
            }
            flushLatestMarketState(Status.ERROR);
            publish(new Snapshot("ERROR", replayId, currentTimeMsc, status, null, null, null, null, null));
        }
    }

    private void accept(IntrabarM5Processor processor, MarketStructureEngine structureEngine, CausalSessionVwap vwap,
                        Pullback09Setup pullback09,
                        long[] occurrenceNumber, HistoricalCanonicalPriceEvent historical) {
        CanonicalPriceEvent event = historical.priceEvent();
        synchronized (lock) {
            while (status == Status.PAUSED && status != Status.CLOSED) {
                try { lock.wait(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
            if (status == Status.CLOSED) return;
        }
        IntrabarMarketState state = processor.onEvent(event);
        if (state.completedCandle() != null && latestMarketState != null
                && latestMarketState.candle().time() * 1000
                == state.completedCandle().bucketStartTimeMsc()) {
            flushLatestMarketState(status);
        }
        eventsProcessed.increment();
        List<RuleOccurrenceMessage> ruleOccurrences = new ArrayList<>();
        List<SetupEventMessage> setupEvents = pullback09.onEvent(state).stream()
                .map(setupEvent -> new SetupEventMessage(
                        "REPLAY_SETUP_EVENT",
                        setupEvent.eventId(),
                        setupEvent.eventType().name(),
                        setupEvent.symbol(),
                        setupEvent.timeMsc(),
                        setupEvent.candleTimeMsc(),
                        setupEvent.price()))
                .toList();
        state.structureSample().ifPresent(sample -> {
            var update = structureEngine.onSample(sample);
            for (var interaction : update.completedInteractions()) {
                Sma21SameSideMoveAwayRule.occurrenceFor(interaction).ifPresent(occurrence ->
                        ruleOccurrences.add(new RuleOccurrenceMessage(
                                "RULE_OCCURRENCE",
                        occurrence.ruleId() + "-" + occurrenceNumber[0]++,
                                occurrence.ruleId(),
                                occurrence.symbol(),
                                occurrence.timeMsc(),
                                occurrence.price(),
                                occurrence.direction(),
                                occurrence.reference(),
                                occurrence.approachSide(),
                                occurrence.exitSide())));
            }
        });
        double volume = CausalSessionVwap.isEligibleVolume(historical) ? historical.volumeReal() : 0;
        Double vwapValue = vwap.update(event.timeMsc(), event.price(), volume);
        currentTimeMsc = event.timeMsc();
        Candle candle = new Candle(state.candle().bucketStartTimeMsc() / 1000,
                state.candle().open(), state.candle().high(),
                state.candle().low(), state.candle().close(),
                Math.round(vwap.currentVolume()));
        Snapshot snapshot = new Snapshot("MARKET_STATE", replayId, currentTimeMsc, status,
                candle, state.sma9(), state.sma21(), vwapValue,
                vwapValue == null ? null : java.time.Instant.ofEpochMilli(state.candle().bucketStartTimeMsc())
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        latestMarketState = snapshot;
        long bucket = state.candle().bucketStartTimeMsc();
        long now = System.nanoTime();
        if (lastVisualBucket != bucket || now - lastVisualPublishNanos >= visualIntervalNanos) {
            publishLatestMarketState(status);
            lastVisualBucket = bucket;
            lastVisualPublishNanos = now;
        } else {
            coalescedMarketStates.increment();
        }
        ruleOccurrences.forEach(this::publishRuleOccurrence);
        setupEvents.forEach(this::publishSetupEvent);
    }

    private void flushLatestMarketState(Status state) {
        if (latestMarketState == null) return;
        if (latestMarketState == lastPublishedMarketState) return;
        publishLatestMarketState(state);
        lastVisualBucket = latestMarketState.candle().time() * 1000;
        lastVisualPublishNanos = System.nanoTime();
    }

    private void publishLatestMarketState(Status state) {
        Snapshot latest = latestMarketState;
        if (latest == null) return;
        marketStatesPublished.increment();
        lastPublishedMarketState = latest;
        publish(new Snapshot(latest.type(), latest.replayId(), latest.timeMsc(), state,
                latest.candle(), latest.sma9(), latest.sma21(), latest.vwap(), latest.vwapSession()));
    }

    private void publish(Snapshot snapshot) {
        listeners.forEach(listener -> listener.accept(snapshot));
    }

    private void publishRuleOccurrence(RuleOccurrenceMessage occurrence) {
        ruleOccurrenceListeners.forEach(listener -> listener.accept(occurrence));
    }

    private void publishSetupEvent(SetupEventMessage setupEvent) {
        setupEventListeners.forEach(listener -> listener.accept(setupEvent));
    }

    private static List<IntrabarCandleSnapshot> toSnapshots(String symbol, List<Mt5Candle> candles) {
        return candles.stream().map(c -> new IntrabarCandleSnapshot(
                symbol, c.time() * 1000, c.open(), c.high(), c.low(), c.close())).toList();
    }
}
