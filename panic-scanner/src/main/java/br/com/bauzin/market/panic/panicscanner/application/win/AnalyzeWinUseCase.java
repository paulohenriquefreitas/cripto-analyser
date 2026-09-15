package br.com.bauzin.market.panic.panicscanner.application.win;

import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import br.com.bauzin.market.panic.panicscanner.application.ProviderErrorCode;
import br.com.bauzin.market.panic.panicscanner.application.win.marketdata.WinMarketDataProvider;
import br.com.bauzin.market.panic.panicscanner.domain.win.TradeLocation;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinAnalysis;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinCandle;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinFlowSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinMarketState;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinScoreBreakdown;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinSignal;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinStructure;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalChecks;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTechnicalSnapshot;
import br.com.bauzin.market.panic.panicscanner.domain.win.WinTradePlan;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinBreakoutAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinConsolidationAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinExecutionAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinExtensionAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinPullbackAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinReversalAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinStructureAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinTradePlanCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.win.analysis.WinTrendAnalyzer;
import br.com.bauzin.market.panic.panicscanner.domain.win.scoring.WinBuyScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.win.scoring.WinExecutionScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.win.scoring.WinExhaustionScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.win.scoring.WinSellScoreCalculator;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AnalyzeWinUseCase {

    private static final String DEFAULT_CONTRACT = "WIN";

    private final WinMarketDataProvider marketDataClient;
    private final WinTechnicalAnalysisEngine technicalAnalysisEngine;
    private final PanicScannerWinProperties properties;
    private final WinTrendAnalyzer trendAnalyzer = new WinTrendAnalyzer();
    private final WinStructureAnalyzer structureAnalyzer = new WinStructureAnalyzer();
    private final WinExtensionAnalyzer extensionAnalyzer = new WinExtensionAnalyzer();
    private final WinPullbackAnalyzer pullbackAnalyzer = new WinPullbackAnalyzer();
    private final WinBreakoutAnalyzer breakoutAnalyzer = new WinBreakoutAnalyzer();
    private final WinReversalAnalyzer reversalAnalyzer = new WinReversalAnalyzer();
    private final WinConsolidationAnalyzer consolidationAnalyzer = new WinConsolidationAnalyzer();
    private final WinExecutionAnalyzer executionAnalyzer = new WinExecutionAnalyzer();
    private final WinTradePlanCalculator tradePlanCalculator = new WinTradePlanCalculator();
    private final WinExhaustionScoreCalculator exhaustionScoreCalculator = new WinExhaustionScoreCalculator();
    private final WinBuyScoreCalculator buyScoreCalculator = new WinBuyScoreCalculator();
    private final WinSellScoreCalculator sellScoreCalculator = new WinSellScoreCalculator();
    private final WinExecutionScoreCalculator executionScoreCalculator = new WinExecutionScoreCalculator();

    public AnalyzeWinUseCase(WinMarketDataProvider marketDataClient,
                             WinTechnicalAnalysisEngine technicalAnalysisEngine,
                             PanicScannerWinProperties properties) {
        this.marketDataClient = marketDataClient;
        this.technicalAnalysisEngine = technicalAnalysisEngine;
        this.properties = properties;
    }

    public WinAnalysis execute(String contract, String contextTimeframe, String executionTimeframe) {
        String normalizedContract = contract == null || contract.isBlank() ? DEFAULT_CONTRACT : contract.trim().toUpperCase();
        String contextTf = contextTimeframe == null || contextTimeframe.isBlank() ? properties.contextTimeframe() : contextTimeframe;
        String executionTf = executionTimeframe == null || executionTimeframe.isBlank() ? properties.executionTimeframe() : executionTimeframe;
        List<WinCandle> contextCandles = marketDataClient.getIntradayCandles(normalizedContract, contextTf);
        List<WinCandle> executionCandles = marketDataClient.getIntradayCandles(normalizedContract, executionTf);
        if (contextCandles.size() < technicalAnalysisEngine.minimumRequiredCandles()
                || executionCandles.size() < technicalAnalysisEngine.minimumRequiredCandles()) {
            throw new MarketDataProviderException(ProviderErrorCode.INSUFFICIENT_HISTORY);
        }
        WinFlowSnapshot flow = marketDataClient.getFlowSnapshot(normalizedContract);
        return analyze(normalizedContract, contextTf, executionTf, contextCandles, executionCandles, flow);
    }

    WinAnalysis analyze(String contract,
                        String contextTimeframe,
                        String executionTimeframe,
                        List<WinCandle> contextCandles,
                        List<WinCandle> executionCandles,
                        WinFlowSnapshot flow) {
        WinTechnicalSnapshot technical5m = technicalAnalysisEngine.analyze(contextTimeframe, contextCandles);
        WinTechnicalSnapshot technical1m = technicalAnalysisEngine.analyze(executionTimeframe, executionCandles);
        boolean consolidation = consolidationAnalyzer.consolidation(technical5m, properties);
        WinMarketState marketState = trendAnalyzer.analyze(technical5m, properties);
        int trendScore = trendAnalyzer.trendScore(marketState, technical5m, properties);
        int sellerExhaustionScore = exhaustionScoreCalculator.sellerExhaustion(technical5m, flow, properties);
        int buyerExhaustionScore = exhaustionScoreCalculator.buyerExhaustion(technical5m, flow, properties);

        boolean overextendedUp = extensionAnalyzer.overextendedUp(technical5m, properties);
        boolean overextendedDown = extensionAnalyzer.overextendedDown(technical5m, properties);
        boolean buySetup = pullbackAnalyzer.buySetupWithoutTrigger(marketState, technical5m, properties);
        boolean sellSetup = pullbackAnalyzer.sellSetupWithoutTrigger(marketState, technical5m, properties);
        boolean buyTriggered = pullbackAnalyzer.buyPullback(marketState, technical5m, technical1m, properties);
        boolean sellTriggered = pullbackAnalyzer.sellPullback(marketState, technical5m, technical1m, properties);
        boolean breakoutUp = breakoutAnalyzer.breakoutUp(technical5m, properties);
        boolean breakdownDown = breakoutAnalyzer.breakdownDown(technical5m, properties);
        boolean falseBreakoutUp = breakoutAnalyzer.falseBreakoutUp(technical5m);
        boolean falseBreakdownDown = breakoutAnalyzer.falseBreakdownDown(technical5m);
        boolean reversalCandidateUp = reversalAnalyzer.candidateUp(marketState, technical5m, sellerExhaustionScore);
        boolean reversalCandidateDown = reversalAnalyzer.candidateDown(marketState, technical5m, buyerExhaustionScore);
        boolean reversalConfirmedUp = reversalAnalyzer.confirmedUp(marketState, technical5m, technical1m, flow, sellerExhaustionScore);
        boolean reversalConfirmedDown = reversalAnalyzer.confirmedDown(marketState, technical5m, technical1m, flow, buyerExhaustionScore);

        int buyScore = buyScoreCalculator.calculate(marketState, technical5m, buyerExhaustionScore);
        int sellScore = sellScoreCalculator.calculate(marketState, technical5m, sellerExhaustionScore);
        int executionScore = sellScore >= buyScore
                ? executionScoreCalculator.calculateSell(technical5m, technical1m, flow, sellSetup || sellTriggered)
                : executionScoreCalculator.calculateBuy(technical5m, technical1m, flow, buySetup || buyTriggered);
        TradeLocation tradeLocation = executionAnalyzer.tradeLocation(technical5m, overextendedUp || overextendedDown);
        WinSignal provisionalSignal = sellScore >= buyScore ? WinSignal.SELL : WinSignal.BUY;
        WinTradePlan provisionalPlan = tradePlanCalculator.calculate(provisionalSignal, technical5m, properties);
        boolean rewardRiskAccepted = tradePlanCalculator.rewardRiskAccepted(provisionalPlan, properties);

        WinExecutionAnalyzer.Decision decision = executionAnalyzer.decide(new WinExecutionAnalyzer.WinDecisionInput(
                marketState,
                consolidation || marketState == WinMarketState.CONSOLIDATION,
                overextendedUp,
                overextendedDown,
                buySetup,
                sellSetup,
                buyTriggered,
                sellTriggered,
                breakoutUp,
                breakdownDown,
                falseBreakoutUp,
                falseBreakdownDown,
                reversalCandidateUp,
                reversalCandidateDown,
                reversalConfirmedUp,
                reversalConfirmedDown,
                tradeLocation,
                executionScore,
                provisionalPlan,
                rewardRiskAccepted), properties);

        WinTradePlan tradePlan = decision.tradeAllowed() ? provisionalPlan : WinTradePlan.empty();
        WinStructure structure = structureAnalyzer.analyze(technical5m, technical1m, consolidation);
        WinScoreBreakdown scores = new WinScoreBreakdown(trendScore, buyScore, sellScore, executionScore, sellerExhaustionScore, buyerExhaustionScore);
        WinTechnicalChecks checks = new WinTechnicalChecks(
                marketState == WinMarketState.STRONG_UPTREND || marketState == WinMarketState.UPTREND
                        || marketState == WinMarketState.STRONG_DOWNTREND || marketState == WinMarketState.DOWNTREND,
                buySetup || sellSetup || buyTriggered || sellTriggered,
                buySetup || sellSetup,
                buyTriggered || sellTriggered,
                overextendedUp || overextendedDown,
                reversalCandidateUp || reversalCandidateDown,
                reversalConfirmedUp || reversalConfirmedDown,
                breakoutUp || breakdownDown,
                falseBreakoutUp || falseBreakdownDown,
                rewardRiskAccepted,
                flow.available());

        return new WinAnalysis(
                contract,
                OffsetDateTime.now(),
                contextTimeframe,
                executionTimeframe,
                contextCandles.get(contextCandles.size() - 1).close(),
                marketState,
                decision.signal(),
                decision.executionStatus(),
                decision.setupType(),
                tradeLocation,
                scores,
                technical5m,
                technical1m,
                structure,
                flow,
                tradePlan,
                checks,
                reasons(marketState, decision, tradeLocation, checks, rewardRiskAccepted));
    }

    private List<String> reasons(WinMarketState marketState,
                                 WinExecutionAnalyzer.Decision decision,
                                 TradeLocation tradeLocation,
                                 WinTechnicalChecks checks,
                                 boolean rewardRiskAccepted) {
        List<String> reasons = new ArrayList<>();
        reasons.add(switch (marketState) {
            case STRONG_UPTREND -> "Contexto comprador forte no 5 minutos";
            case UPTREND -> "Contexto comprador no 5 minutos";
            case UPTREND_WEAKENING -> "Tendência de alta começa a enfraquecer";
            case CONSOLIDATION -> "Mercado em lateralização; sem direção limpa";
            case DOWNTREND_WEAKENING -> "Tendência de baixa começa a enfraquecer";
            case DOWNTREND -> "Contexto vendedor no 5 minutos";
            case STRONG_DOWNTREND -> "Contexto vendedor forte no 5 minutos";
        });
        if (checks.overextended()) reasons.add("Movimento atual está esticado; aguarde melhor localização");
        if (decision.executionStatus().name().contains("SETUP")) reasons.add("Setup técnico existe, mas ainda exige gatilho ou qualidade mínima");
        if (decision.executionStatus().name().contains("TRIGGERED")) reasons.add("Gatilho de 1 minuto confirmou retomada do movimento");
        if (decision.executionStatus().name().contains("REVERSAL_CANDIDATE")) reasons.add("Reversão ainda é candidata; não há confirmação suficiente para inverter a mão");
        if (decision.setupType().name().contains("FALSE")) reasons.add("Preço retornou contra o rompimento; tratar como falso rompimento");
        if (!rewardRiskAccepted && decision.executionStatus().name().contains("TRIGGERED")) {
            reasons.add("Setup técnico existe, mas o risco/retorno é insuficiente");
        }
        if (!checks.flowDataAvailable()) reasons.add("Dados de fluxo/agressão indisponíveis; score normalizado sem fluxo");
        if (decision.signal() == WinSignal.WAIT && tradeLocation == TradeLocation.VERY_POOR) {
            reasons.add("Tendência confirmada, mas a entrada está atrasada e distante das médias");
        }
        return reasons;
    }
}
