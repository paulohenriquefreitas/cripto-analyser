package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureConfig;
import br.com.bauzin.market.panic.panicscanner.application.win.structure.MarketStructureEngine;
import br.com.bauzin.market.panic.panicscanner.domain.replay.OutcomeSpecification;
import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.CanonicalPriceEvent;
import br.com.bauzin.market.panic.panicscanner.domain.win.intrabar.IntrabarCandleSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalStructuralOutcomeReplayRunnerTest {
    private static final long START = 20 * 300_000L;

    @Test
    void createsLongOccurrenceOnlyAfterTouchedSameSideCompletionAndExcludesCausalLast() throws Exception {
        CanonicalStructuralOutcomeReplayResult result = run(List.of(
                event(0, 115), event(1, 112), event(2, 109),
                event(3, 102), event(4, 121), event(4, 130)));

        assertThat(result.occurrences()).hasSize(1);
        assertThat(result.occurrences().getFirst().direction()).isEqualTo(RuleDirection.LONG);
        assertThat(result.occurrences().getFirst().entryPrice()).isEqualTo(121);
        assertThat(result.outcomes().getFirst().mfe()).isEqualTo(9);
    }

    @Test
    void repeatedExecutionIsDeterministic() throws Exception {
        List<CanonicalPriceEvent> events = List.of(
                event(0, 115), event(1, 112), event(2, 109),
                event(3, 102), event(4, 121), event(4, 130));

        assertThat(run(events)).isEqualTo(run(events));
    }

    @Test
    void createsShortOccurrenceForBelowTouchAndSameSideExit() throws Exception {
        CanonicalStructuralOutcomeReplayResult result = run(List.of(
                event(0, 85), event(1, 88), event(2, 91),
                event(3, 98), event(4, 79), event(5, 70)));

        assertThat(result.occurrences()).hasSize(1);
        assertThat(result.occurrences().getFirst().direction()).isEqualTo(RuleDirection.SHORT);
    }

    @Test
    void doesNotCreateOccurrenceForOppositeSideExitOrUntouchedInteraction() throws Exception {
        CanonicalStructuralOutcomeReplayResult crossed = run(List.of(
                event(0, 115), event(1, 112), event(2, 109),
                event(3, 102), event(4, 98), event(5, 79)));
        CanonicalStructuralOutcomeReplayResult untouched = run(List.of(
                event(0, 115), event(1, 112), event(2, 109),
                event(3, 105), event(4, 121)));

        assertThat(crossed.occurrences()).isEmpty();
        assertThat(untouched.occurrences()).isEmpty();
    }

    @Test
    void rejectsInsufficientWarmupBeforeEvaluatingOccurrences() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new CanonicalStructuralOutcomeReplayRunner(new MarketStructureEngine())
                        .run(consumerSource(List.of(event(0, 100))), "WIN", START,
                                warmup().subList(0, 19),
                                new OutcomeSpecification(5, 5, Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20 closed M5 candles");
    }

    private static CanonicalStructuralOutcomeReplayResult run(List<CanonicalPriceEvent> events)
            throws Exception {
        return new CanonicalStructuralOutcomeReplayRunner(
                new MarketStructureEngine(new MarketStructureConfig(10, 20, 2.5, 3, 5)))
                .run(consumerSource(events), "WIN", START, warmup(), new OutcomeSpecification(
                        5, 50, Duration.ofSeconds(10)));
    }

    private static CanonicalPriceEvent event(long sequenceOffset, double price) {
        return new CanonicalPriceEvent("WIN", START + sequenceOffset, price);
    }

    private static List<IntrabarCandleSnapshot> warmup() {
        return java.util.stream.LongStream.range(0, 20)
                .mapToObj(index -> new IntrabarCandleSnapshot(
                        "WIN", index * 300_000, 100, 100, 100, 100))
                .toList();
    }

    private static ReplaySource<CanonicalPriceEvent> consumerSource(List<CanonicalPriceEvent> events) {
        return events::forEach;
    }
}
