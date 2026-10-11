package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.ta4j.rules.PreviousBarAppliedRule;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreviousBarAppliedRuleTest {
    @Test void evaluatesOnlyPreviousIndexAndPreservesTradingRecord() {
        var rule = mock(Rule.class);
        var record = mock(TradingRecord.class);
        when(rule.isSatisfied(9, record)).thenReturn(true);
        assertThat(new PreviousBarAppliedRule(rule, 1).isSatisfied(10, record)).isTrue();
        verify(rule).isSatisfied(9, record);
        verifyNoMoreInteractions(rule);
    }

    @Test void insufficientHistoryNeverEvaluatesNegativeOrCurrentIndex() {
        var rule = mock(Rule.class);
        assertThat(new PreviousBarAppliedRule(rule, 1).isSatisfied(0)).isFalse();
        verifyNoInteractions(rule);
        assertThatIllegalArgumentException().isThrownBy(() -> new PreviousBarAppliedRule(rule, 0));
    }
}
