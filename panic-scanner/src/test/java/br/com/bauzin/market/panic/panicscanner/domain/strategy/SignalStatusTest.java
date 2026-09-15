package br.com.bauzin.market.panic.panicscanner.domain.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalStatusTest {

    @Test
    void shouldExposeSignalStatusNames() {
        assertThat(SignalStatus.values())
                .containsExactly(SignalStatus.QUALIFIED, SignalStatus.WATCH, SignalStatus.REJECTED);
    }
}
