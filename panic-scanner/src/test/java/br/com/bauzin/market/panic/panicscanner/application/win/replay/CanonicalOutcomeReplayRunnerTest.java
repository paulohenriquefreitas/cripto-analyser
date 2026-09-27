package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.*;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalOutcomeReplayRunnerTest {
    @Test
    void streamsCanonicalEventsIntoEvaluatorWithoutDeduplication() throws Exception {
        RuleOccurrence occurrence = new RuleOccurrence("manual", "synthetic", "WIN", 1_000, 100, RuleDirection.LONG);
        OutcomeEvaluator evaluator = new OutcomeEvaluator(new OutcomeSpecification(5, 5, Duration.ofMillis(100)));
        List<CanonicalPriceEvent> history = List.of(
                new CanonicalPriceEvent("WIN", 999, 90),
                new CanonicalPriceEvent("WIN", 1_000, 100),
                new CanonicalPriceEvent("WIN", 1_000, 100),
                new CanonicalPriceEvent("WIN", 1_001, 106),
                new CanonicalPriceEvent("WIN", 1_002, 80));

        OutcomeResult result = new CanonicalOutcomeReplayRunner().run(
                consumerSource(history), evaluator, occurrence, 1);

        assertThat(result.status()).isEqualTo(OutcomeStatus.TARGET_FIRST);
        assertThat(result.targetHitTime()).hasValue(1_001);
        assertThat(result.finalPrice()).hasValue(106);
        assertThat(result.mfe()).isEqualTo(6);
        assertThat(evaluator.results()).hasSize(1);
    }

    @Test
    void sameCanonicalSequenceIsDeterministicAndRepeatedEventsRemainDistinct() throws Exception {
        List<CanonicalPriceEvent> history = List.of(
                new CanonicalPriceEvent("WIN", 1_000, 100),
                new CanonicalPriceEvent("WIN", 1_000, 100),
                new CanonicalPriceEvent("WIN", 1_001, 103));
        OutcomeResult first = run(history);
        OutcomeResult second = run(history);
        assertThat(first).isEqualTo(second);
        assertThat(first.finalTimeMsc()).hasValue(1_001);
    }

    @Test
    void sourceEndFinalizesUnresolvedOccurrence() throws Exception {
        OutcomeEvaluator evaluator = new OutcomeEvaluator(
                new OutcomeSpecification(50, 50, Duration.ofSeconds(1)));
        OutcomeResult result = new CanonicalOutcomeReplayRunner().run(
                consumerSource(List.of(new CanonicalPriceEvent("WIN", 1_000, 100))),
                evaluator,
                new RuleOccurrence("manual-end", "synthetic", "WIN", 1_000, 100, RuleDirection.LONG),
                0);

        assertThat(result.status()).isEqualTo(OutcomeStatus.REPLAY_ENDED);
        assertThat(result.terminationReason()).isEqualTo(TerminationReason.REPLAY_ENDED);
    }

    private static OutcomeResult run(List<CanonicalPriceEvent> history) throws Exception {
        OutcomeEvaluator evaluator = new OutcomeEvaluator(
                new OutcomeSpecification(50, 50, Duration.ofSeconds(1)));
        return new CanonicalOutcomeReplayRunner().run(
                consumerSource(history), evaluator,
                new RuleOccurrence("manual", "synthetic", "WIN", 1_000, 100, RuleDirection.LONG), 0);
    }

    private static ReplaySource<CanonicalPriceEvent> consumerSource(List<CanonicalPriceEvent> history) {
        return consumer -> history.forEach(consumer);
    }
}
