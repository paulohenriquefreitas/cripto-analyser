package br.com.bauzin.market.panic.panicscanner.domain.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.flow.FlowSnapshot;

public record ReplayFlowSnapshots(
        FlowSnapshot oneSecond,
        FlowSnapshot threeSeconds,
        FlowSnapshot fiveSeconds,
        FlowSnapshot tenSeconds) {
}
