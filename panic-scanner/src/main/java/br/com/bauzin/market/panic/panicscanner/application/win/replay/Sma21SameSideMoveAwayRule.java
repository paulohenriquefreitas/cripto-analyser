package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.replay.RuleDirection;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceReference;
import br.com.bauzin.market.panic.panicscanner.domain.win.structure.PriceSide;

import java.util.Objects;
import java.util.Optional;

public final class Sma21SameSideMoveAwayRule {
    public static final String RULE_ID = "SMA21_SAME_SIDE_MOVE_AWAY";

    private Sma21SameSideMoveAwayRule() {
    }

    public static Optional<Occurrence> occurrenceFor(MovingAverageInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        if (interaction.reference() != PriceReference.SMA21
                || !interaction.touched()
                || interaction.approachSide() == PriceSide.AT
                || interaction.approachSide() != interaction.exitSide()) {
            return Optional.empty();
        }
        RuleDirection direction = interaction.approachSide() == PriceSide.ABOVE
                ? RuleDirection.LONG
                : RuleDirection.SHORT;
        return Optional.of(new Occurrence(
                RULE_ID,
                interaction.symbol(),
                interaction.endTimeMsc(),
                interaction.exitPrice(),
                direction,
                interaction.reference(),
                interaction.approachSide(),
                interaction.exitSide()));
    }

    public record Occurrence(
            String ruleId,
            String symbol,
            long timeMsc,
            double price,
            RuleDirection direction,
            PriceReference reference,
            PriceSide approachSide,
            PriceSide exitSide) {
    }
}
