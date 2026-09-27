package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;

import java.io.IOException;
import java.util.Objects;

/** Streams canonical historical LAST events through the generic outcome evaluator. */
public final class CanonicalOutcomeReplayRunner {
    public OutcomeResult run(
            ReplaySource<CanonicalPriceEvent> source,
            OutcomeEvaluator evaluator,
            RuleOccurrence occurrence,
            long occurrenceSequence) throws IOException, InterruptedException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(evaluator, "evaluator must not be null");
        Objects.requireNonNull(occurrence, "occurrence must not be null");
        if (occurrenceSequence < 0) {
            throw new IllegalArgumentException("occurrenceSequence must not be negative");
        }

        CanonicalPriceEventAdapter adapter = new CanonicalPriceEventAdapter();
        boolean[] registeredHolder = {false};
        try (source) {
            source.stream(event -> {
                PriceEvent priceEvent = adapter.adapt(event);
                if (!registeredHolder[0]
                        && priceEvent.timeMsc() == occurrence.timeMsc()
                        && priceEvent.sequence() >= occurrenceSequence) {
                    evaluator.registerAtSequence(occurrence, priceEvent.sequence() - 1);
                    registeredHolder[0] = true;
                }
                evaluator.onPrice(priceEvent);
            });
        } finally {
            evaluator.finishReplay();
        }
        if (!registeredHolder[0]) {
            throw new IllegalArgumentException("Occurrence was not reached by canonical source");
        }
        return evaluator.result(occurrence.occurrenceId());
    }
}
