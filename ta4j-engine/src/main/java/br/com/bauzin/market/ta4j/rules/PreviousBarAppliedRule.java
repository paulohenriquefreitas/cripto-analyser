package br.com.bauzin.market.ta4j.rules;

import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.rules.AbstractRule;

import java.util.Objects;

/** Compatibility adapter: TA4J 0.22.6 has no previous-bar rule decorator. */
public final class PreviousBarAppliedRule extends AbstractRule {
    private final Rule rule;
    private final int bars;

    public PreviousBarAppliedRule(Rule rule, int bars) {
        this.rule = Objects.requireNonNull(rule);
        if (bars < 1) throw new IllegalArgumentException("Previous bars must be positive");
        this.bars = bars;
    }

    @Override
    public boolean isSatisfied(int index, TradingRecord tradingRecord) {
        return index >= bars && rule.isSatisfied(index - bars, tradingRecord);
    }
}
