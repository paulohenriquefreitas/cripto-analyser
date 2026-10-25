package br.com.bauzin.market.panic.panicscanner.application.win.strategy;

/** Experimental observation, not an execution signal. */
public record StructureReactionEvent(long timeMsc, double levelPrice, String levelType,
                                     String reactionType, long delta500ms) {}
