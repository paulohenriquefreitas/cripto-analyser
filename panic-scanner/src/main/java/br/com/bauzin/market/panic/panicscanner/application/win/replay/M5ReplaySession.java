package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide;
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
                           Candle candle, Double sma9, Double sma21, Double vwap, String vwapSession,
                           ClosedEma9 closedEma9) {
        public Snapshot(String type, String replayId, long timeMsc, Status status,
                        Candle candle, Double sma9, Double sma21, Double vwap, String vwapSession) {
            this(type, replayId, timeMsc, status, candle, sma9, sma21, vwap, vwapSession, null);
        }
    }
    public record ClosedEma9(long time, double value, String reversal, Double previousValue, long availableAtTimeMsc) {
        public ClosedEma9(long time, double value) { this(time, value, null, null, (time + 300) * 1000); }
    }
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
            double price,
            Setup91Breakout.SignalCandle signal,
            Long sequence,
            Double breakoutPrice) {
        public SetupEventMessage(String type, String eventId, String setupType, String symbol,
                                 long timeMsc, long candleTimeMsc, double price) {
            this(type, eventId, setupType, symbol, timeMsc, candleTimeMsc, price, null, null, null);
        }
    }
    public record Metrics(long eventsProcessed, long marketStatesPublished, long coalescedMarketStates) {}

    private static final long DEFAULT_VISUAL_INTERVAL_NANOS = 50_000_000L;

    private final String replayId = UUID.randomUUID().toString();
    private final Path historyFile;
    private final String requestedSymbol;
    private final Mt5HistoricalPriceSource source;
    private final Pullback09ResearchEngine researchEngine = new Pullback09ResearchEngine();
    private final Setup91 setup91 = new Setup91();
    private final Setup91Breakout setup91Breakout = new Setup91Breakout(setup91);
    private final Setup91TriggerResearch trades = new Setup91TriggerResearch(Setup91TriggerResearch.Mode.PARTIAL_1R_BREAKEVEN_TARGET_3_5R);
    private final List<Consumer<Setup91TriggerResearch.TradeEvent>> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<Setup91TriggerResearch.TradeEvent> pendingTradeEvents = new ArrayList<>();
    private ClosedEma9 closedEma9;
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
        trades.setTradeListener(pendingTradeEvents::add);
    }

    public String replayId() { return replayId; }
    public Status status() { return status; }
    public long currentTimeMsc() { return currentTimeMsc; }
    public Metrics metrics() {
        return new Metrics(eventsProcessed.sum(), marketStatesPublished.sum(), coalescedMarketStates.sum());
    }
    public Pullback09ResearchEngine researchEngine() { return researchEngine; }
    public List<Pullback09ResearchRecord> researchRecords() { return researchEngine.researchRecords(); }
    public String researchCsv() { return researchEngine.toCsv(); }

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

    public void addTradeListener(Consumer<Setup91TriggerResearch.TradeEvent> listener) { tradeListeners.add(listener); }
    public void removeTradeListener(Consumer<Setup91TriggerResearch.TradeEvent> listener) { tradeListeners.remove(listener); }
    public List<Setup91TriggerResearch.Result> tradeResults() { return trades.completedResults(); }
    private void publishTrades() {
        pendingTradeEvents.forEach(event -> tradeListeners.forEach(listener -> listener.accept(event)));
        pendingTradeEvents.clear();
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
            Pullback09CausalProcessor processor = new Pullback09CausalProcessor(researchEngine);
            MarketStructureEngine structureEngine = new MarketStructureEngine();
            long[] occurrenceNumber = {0};
            source.streamHistorical(reader, header -> {
                if (!requestedSymbol.equalsIgnoreCase(header.symbol())) {
                    throw new IllegalArgumentException("History symbol differs from requested symbol");
                }
                processor.warmUp(header);
                trades.warmUp(header.symbol(), header.startMsc(), header.warmup().stream().map(c ->
                        new br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot(
                                header.symbol(), c.time() * 1000L, c.open(), c.high(), c.low(), c.close())).toList());
                // Exactly the Batch warm-up: closed candles seed the detector, never arm signals.
                for (var candle : header.warmup()) {
                    setup91.onCandleClosed(new br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot(
                            header.symbol(), candle.time() * 1000L, candle.open(), candle.high(), candle.low(), candle.close()),
                            candle.time() * 1000L + br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.M5Bucket.DURATION_MSC);
                }
                structureEngine.reset();
            }, event -> accept(processor, structureEngine, occurrenceNumber, event));
            if (status != Status.CLOSED) { trades.finishDay(); publishTrades(); }
            researchEngine.finish();
            synchronized (lock) {
                if (status != Status.CLOSED) status = Status.COMPLETED;
            }
            flushLatestMarketState(Status.COMPLETED);
            publish(new Snapshot("COMPLETED", replayId, currentTimeMsc, status, null, null, null, null, null));
        } catch (Exception ex) {
            researchEngine.finish();
            synchronized (lock) {
                if (status != Status.CLOSED) status = Status.ERROR;
            }
            flushLatestMarketState(Status.ERROR);
            publish(new Snapshot("ERROR", replayId, currentTimeMsc, status, null, null, null, null, null));
        }
    }

    private void accept(Pullback09CausalProcessor processor, MarketStructureEngine structureEngine,
                        long[] occurrenceNumber, HistoricalCanonicalPriceEvent historical) {
        CanonicalPriceEvent event = historical.priceEvent();
        synchronized (lock) {
            while (status == Status.PAUSED && status != Status.CLOSED) {
                try { lock.wait(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
            if (status == Status.CLOSED) return;
        }
        boolean positionWasOpen = trades.hasOpenPosition();
        trades.onLast(historical);
        var causal = processor.accept(historical);
        IntrabarMarketState state = causal.state();
        if (state.completedCandle() != null) {
            if (latestMarketState != null
                    && latestMarketState.candle().time() * 1000
                    == state.completedCandle().bucketStartTimeMsc()) {
                flushLatestMarketState(status);
            }
        }
        eventsProcessed.increment();
        Double vwapValue = causal.vwap();
        currentTimeMsc = event.timeMsc();
        // Reuse the same causal M5 state and original LAST sequence as Batch.
        var setup91Update = setup91Breakout.onEvent(state, historical.sequence(),
                positionWasOpen || trades.hasOpenPosition());
        setup91Update.observation().ifPresent(observation -> {
            if (Double.isFinite(observation.ema9())) {
                closedEma9 = new ClosedEma9(observation.candle().bucketStartTimeMsc() / 1000, observation.ema9(),
                        observation.confirmedReversal().map(side -> side == Setup91.Candidate.BUY ? "UP" : "DOWN").orElse(null),
                        observation.previousEma9(), observation.availableAtTimeMsc());
            }
        });

        for (var trigger : setup91Update.events()) {
            if (trigger.type().name().endsWith("_TRIGGERED")) trades.onTrigger(trigger);
        }
        List<RuleOccurrenceMessage> ruleOccurrences = new ArrayList<>();
        // Replay publishes only Setup91 research markers. Pullback09 research remains independent.
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
        Candle candle = new Candle(state.candle().bucketStartTimeMsc() / 1000,
                state.candle().open(), state.candle().high(),
                state.candle().low(), state.candle().close(),
                Math.round(causal.volume()));
        Snapshot snapshot = new Snapshot("MARKET_STATE", replayId, currentTimeMsc, status,
                candle, state.sma9(), state.sma21(), vwapValue,
                vwapValue == null ? null : java.time.Instant.ofEpochMilli(state.candle().bucketStartTimeMsc())
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(), closedEma9);
        latestMarketState = snapshot;
        long bucket = state.candle().bucketStartTimeMsc();
        long now = System.nanoTime();
        if (lastVisualBucket != bucket || now - lastVisualPublishNanos >= visualIntervalNanos
                || !setup91Update.events().isEmpty() || !pendingTradeEvents.isEmpty()) {
            publishLatestMarketState(status);
            lastVisualBucket = bucket;
            lastVisualPublishNanos = now;
        } else {
            coalescedMarketStates.increment();
        }
        publishTrades();
        ruleOccurrences.forEach(this::publishRuleOccurrence);
        for (var setupEvent : setup91Update.events()) {
            var signal = setupEvent.signal();
            publishSetupEvent(new SetupEventMessage("REPLAY_SETUP_EVENT",
                    setupEvent.type().name() + "-" + signal.candle().bucketStartTimeMsc() + "-" + setupEvent.sequence(),
                    setupEvent.type().name(), signal.candle().symbol(), setupEvent.timeMsc(),
                    signal.candle().bucketStartTimeMsc(), setupEvent.lastPrice(), signal, setupEvent.sequence(),
                    setupEvent.type().name().endsWith("_TRIGGERED") ? setupEvent.lastPrice() : null));
        }
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
                latest.candle(), latest.sma9(), latest.sma21(), latest.vwap(), latest.vwapSession(), latest.closedEma9()));
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

}
