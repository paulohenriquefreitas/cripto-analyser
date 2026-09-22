package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import br.com.bauzin.market.panic.panicscanner.domain.win.structure.MovingAverageInteraction;

@FunctionalInterface
public interface StructureReplayObserver {
    StructureReplayObserver NONE = interaction -> {};

    void onInteractionCompleted(MovingAverageInteraction interaction);
}
