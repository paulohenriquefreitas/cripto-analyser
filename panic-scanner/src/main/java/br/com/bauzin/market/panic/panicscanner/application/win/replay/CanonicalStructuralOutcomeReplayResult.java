package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.OutcomeResult;
import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleOccurrence;

import java.util.List;

public record CanonicalStructuralOutcomeReplayResult(
        List<RuleOccurrence> occurrences,
        List<OutcomeResult> outcomes) {
    public CanonicalStructuralOutcomeReplayResult {
        occurrences = List.copyOf(occurrences);
        outcomes = List.copyOf(outcomes);
        if (occurrences.size() != outcomes.size()) {
            throw new IllegalArgumentException("occurrences and outcomes must have the same size");
        }
    }
}
