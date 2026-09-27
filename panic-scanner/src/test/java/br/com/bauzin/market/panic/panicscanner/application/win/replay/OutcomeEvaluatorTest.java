package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutcomeEvaluatorTest {
    private static final OutcomeSpecification SPEC =
            new OutcomeSpecification(10, 10, Duration.ofMillis(100));

    @Test
    void longTargetFirst() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_010, 111, 1));
        assertThat(evaluator.result("a").status()).isEqualTo(OutcomeStatus.TARGET_FIRST);
    }

    @Test
    void longStopFirst() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_010, 89, 1));
        assertThat(evaluator.result("a").firstBarrier()).isEqualTo(FirstBarrier.STOP);
    }

    @Test
    void shortTargetFirst() {
        OutcomeEvaluator evaluator = evaluator(shortOccurrence("a", 1_000));
        evaluator.onPrice(price(1_010, 89, 1));
        assertThat(evaluator.result("a").status()).isEqualTo(OutcomeStatus.TARGET_FIRST);
    }

    @Test
    void shortStopFirst() {
        OutcomeEvaluator evaluator = evaluator(shortOccurrence("a", 1_000));
        evaluator.onPrice(price(1_010, 111, 1));
        assertThat(evaluator.result("a").firstBarrier()).isEqualTo(FirstBarrier.STOP);
    }

    @Test
    void calculatesLongMfeAndMae() {
        OutcomeEvaluator evaluator = evaluator(new RuleOccurrence("a", "r", "WIN", 1_000, 100, RuleDirection.LONG));
        evaluator.onPrice(price(1_010, 105, 1));
        evaluator.onPrice(price(1_020, 97, 2));
        evaluator.onPrice(price(1_030, 103, 3));
        OutcomeResult result = evaluator.result("a");
        assertThat(result.mfe()).isEqualTo(5);
        assertThat(result.mae()).isEqualTo(3);
        assertThat(result.timeToMfe()).hasValue(10);
        assertThat(result.timeToMae()).hasValue(20);
    }

    @Test
    void calculatesShortMfeAndMae() {
        OutcomeEvaluator evaluator = evaluator(new RuleOccurrence("a", "r", "WIN", 1_000, 100, RuleDirection.SHORT));
        evaluator.onPrice(price(1_010, 95, 1));
        evaluator.onPrice(price(1_020, 103, 2));
        OutcomeResult result = evaluator.result("a");
        assertThat(result.mfe()).isEqualTo(5);
        assertThat(result.mae()).isEqualTo(3);
    }

    @Test
    void includesTargetAndStopLimits() {
        OutcomeEvaluator target = evaluator(longOccurrence("target", 1_000));
        target.onPrice(price(1_001, 110, 1));
        OutcomeEvaluator stop = evaluator(longOccurrence("stop", 1_000));
        stop.onPrice(price(1_001, 90, 1));
        assertThat(target.result("target").status()).isEqualTo(OutcomeStatus.TARGET_FIRST);
        assertThat(stop.result("stop").status()).isEqualTo(OutcomeStatus.STOP_FIRST);
    }

    @Test
    void reachesHorizonAfterInclusiveBoundary() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_100, 105, 1));
        evaluator.finishReplay();
        assertThat(evaluator.result("a").status()).isEqualTo(OutcomeStatus.REPLAY_ENDED);

        OutcomeEvaluator expired = evaluator(longOccurrence("b", 1_000));
        expired.onPrice(price(1_101, 105, 1));
        assertThat(expired.result("b").status()).isEqualTo(OutcomeStatus.HORIZON_REACHED);
    }

    @Test
    void finishesAtReplayEnd() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.finishReplay();
        assertThat(evaluator.result("a").terminationReason()).isEqualTo(TerminationReason.REPLAY_ENDED);
    }

    @Test
    void supportsMultipleOccurrencesWithoutMixing() {
        OutcomeEvaluator evaluator = new OutcomeEvaluator(SPEC);
        evaluator.register(longOccurrence("long", 1_000));
        evaluator.register(shortOccurrence("short", 1_000));
        evaluator.onPrice(price(1_010, 111, 1));
        assertThat(evaluator.result("long").status()).isEqualTo(OutcomeStatus.TARGET_FIRST);
        assertThat(evaluator.result("short").status()).isEqualTo(OutcomeStatus.STOP_FIRST);
    }

    @Test
    void sequenceOrdersSameMillisecondAndRegistrationExcludesPriorEvent() {
        OutcomeEvaluator evaluator = new OutcomeEvaluator(SPEC);
        evaluator.onPrice(price(1_000, 100, 1));
        evaluator.register(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_000, 105, 2));
        evaluator.onPrice(price(1_001, 110, 3));
        assertThat(evaluator.result("a").targetHitTime()).hasValue(1_001);
    }

    @Test
    void eventBeforeOccurrenceDoesNotParticipate() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(999, 90, 1));
        assertThat(evaluator.result("a").mfe()).isZero();
        assertThat(evaluator.result("a").mae()).isZero();
    }

    @Test
    void finalizedResultDoesNotChangeWithFutureEvents() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_001, 110, 1));
        OutcomeResult finished = evaluator.result("a");
        evaluator.onPrice(price(1_002, 80, 2));
        assertThat(evaluator.result("a")).isEqualTo(finished);
    }

    @Test
    void rejectsOutOfOrderAndIncompatibleSymbols() {
        OutcomeEvaluator evaluator = evaluator(longOccurrence("a", 1_000));
        evaluator.onPrice(price(1_010, 101, 2));
        assertThatThrownBy(() -> evaluator.onPrice(price(1_010, 101, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        OutcomeEvaluator incompatible = evaluator(longOccurrence("b", 1_000));
        assertThatThrownBy(() -> incompatible.onPrice(new PriceEvent("DOL", 1_001, 101, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameSequenceProducesSameResults() {
        List<PriceEvent> events = List.of(price(1_001, 103, 1), price(1_002, 97, 2));
        OutcomeEvaluator first = evaluator(longOccurrence("a", 1_000));
        OutcomeEvaluator second = evaluator(longOccurrence("a", 1_000));
        events.forEach(first::onPrice);
        events.forEach(second::onPrice);
        assertThat(first.result("a")).isEqualTo(second.result("a"));
    }

    private static OutcomeEvaluator evaluator(RuleOccurrence occurrence) {
        OutcomeEvaluator evaluator = new OutcomeEvaluator(SPEC);
        evaluator.register(occurrence);
        return evaluator;
    }

    private static RuleOccurrence longOccurrence(String id, long time) {
        return new RuleOccurrence(id, "rule", "WIN", time, 100, RuleDirection.LONG);
    }

    private static RuleOccurrence shortOccurrence(String id, long time) {
        return new RuleOccurrence(id, "rule", "WIN", time, 100, RuleDirection.SHORT);
    }

    private static PriceEvent price(long time, double value, long sequence) {
        return new PriceEvent("WIN", time, value, sequence);
    }
}
