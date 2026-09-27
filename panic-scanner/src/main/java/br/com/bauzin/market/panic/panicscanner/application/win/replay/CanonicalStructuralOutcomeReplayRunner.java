package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.intrabar.IntrabarM5Processor;
import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.OutcomeResult;
import br.com.bauzin.market.panic.panicscanner.domain.replay.OutcomeSpecification;
import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleOccurrence;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Composes the canonical LAST stream, M5 structure and the generic outcome evaluator.
 * The LAST that completes an interaction is causal evidence for the occurrence, but is
 * excluded from its post-occurrence outcome by registering the sequence as a watermark.
 */
public final class CanonicalStructuralOutcomeReplayRunner {
    public static final String RULE_ID = Sma21SameSideMoveAwayRule.RULE_ID;

    private final MarketStructureEngine structureEngine;

    public CanonicalStructuralOutcomeReplayRunner(MarketStructureEngine structureEngine) {
        this.structureEngine = Objects.requireNonNull(structureEngine, "structureEngine must not be null");
    }

    public CanonicalStructuralOutcomeReplayResult run(
            ReplaySource<CanonicalPriceEvent> source,
            String symbol,
            long analysisStartTimeMsc,
            List<IntrabarCandleSnapshot> warmup,
            OutcomeSpecification specification)
            throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(warmup, "warmup must not be null");
        Objects.requireNonNull(specification, "specification must not be null");
        if (analysisStartTimeMsc < 0) {
            throw new IllegalArgumentException("analysisStartTimeMsc must not be negative");
        }
        if (warmup.size() < 20) {
            throw new IllegalArgumentException("At least 20 closed M5 candles are required for SMA21 warm-up");
        }

        IntrabarM5Processor processor = new IntrabarM5Processor();
        processor.warmUp(symbol, analysisStartTimeMsc, warmup);
        structureEngine.reset();
        OutcomeEvaluator evaluator = new OutcomeEvaluator(specification);
        CanonicalPriceEventAdapter adapter = new CanonicalPriceEventAdapter();
        List<RuleOccurrence> occurrences = new ArrayList<>();
        long[] occurrenceNumber = {0};

        try (source) {
            source.stream(event -> {
                var priceEvent = adapter.adapt(event);
                evaluator.onPrice(priceEvent);

                var state = processor.onEvent(event);
                state.structureSample().ifPresent(sample -> {
                    var update = structureEngine.onSample(sample);
                    for (MovingAverageInteraction interaction : update.completedInteractions()) {
                        Sma21SameSideMoveAwayRule.occurrenceFor(interaction).ifPresent(ruleOccurrence -> {
                            RuleOccurrence occurrence = new RuleOccurrence(
                                    ruleOccurrence.ruleId() + "-" + occurrenceNumber[0],
                                    ruleOccurrence.ruleId(),
                                    ruleOccurrence.symbol(),
                                    ruleOccurrence.timeMsc(),
                                    ruleOccurrence.price(),
                                    ruleOccurrence.direction());
                            evaluator.registerAtSequence(occurrence, priceEvent.sequence());
                            occurrences.add(occurrence);
                            occurrenceNumber[0]++;
                        });
                    }
                });
            });
        } finally {
            evaluator.finishReplay();
        }

        List<OutcomeResult> outcomes = occurrences.stream()
                .map(occurrence -> evaluator.result(occurrence.occurrenceId()))
                .toList();
        return new CanonicalStructuralOutcomeReplayResult(occurrences, outcomes);
    }
}
