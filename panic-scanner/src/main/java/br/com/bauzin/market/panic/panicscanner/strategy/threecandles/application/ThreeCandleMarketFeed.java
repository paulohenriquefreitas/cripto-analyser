package br.com.bauzin.market.panic.panicscanner.strategy.threecandles.application;

/** Live and replay expose only the causal state accepted by their own pipeline. */
public interface ThreeCandleMarketFeed {
    CandlePipeline pipeline();
}
