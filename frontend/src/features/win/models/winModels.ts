export type WinMarketState =
  | 'STRONG_UPTREND'
  | 'UPTREND'
  | 'UPTREND_WEAKENING'
  | 'CONSOLIDATION'
  | 'DOWNTREND_WEAKENING'
  | 'DOWNTREND'
  | 'STRONG_DOWNTREND';

export type WinSignal = 'BUY' | 'SELL' | 'WAIT';

export type WinExecutionStatus =
  | 'NO_TRADE'
  | 'WATCH_BUY'
  | 'WATCH_SELL'
  | 'BUY_SETUP'
  | 'SELL_SETUP'
  | 'BUY_TRIGGERED'
  | 'SELL_TRIGGERED'
  | 'OVEREXTENDED_UP'
  | 'OVEREXTENDED_DOWN'
  | 'REVERSAL_CANDIDATE_UP'
  | 'REVERSAL_CANDIDATE_DOWN'
  | 'REVERSAL_CONFIRMED_UP'
  | 'REVERSAL_CONFIRMED_DOWN';

export type WinSetupType =
  | 'NONE'
  | 'BUY_PULLBACK'
  | 'SELL_PULLBACK'
  | 'BREAKOUT_UP'
  | 'BREAKDOWN_DOWN'
  | 'WAIT_RETEST'
  | 'FALSE_BREAKOUT_UP'
  | 'FALSE_BREAKOUT_DOWN'
  | 'REVERSAL_UP'
  | 'REVERSAL_DOWN';

export type TradeLocation = 'EXCELLENT' | 'GOOD' | 'ACCEPTABLE' | 'POOR' | 'VERY_POOR';

export type WinScores = {
  trend: number;
  buy: number;
  sell: number;
  execution: number;
  sellerExhaustion: number;
  buyerExhaustion: number;
};

export type WinTechnicalSnapshot = {
  sma9: number;
  sma21: number;
  ema9: number;
  ema21: number;
  rsi9: number;
  atr14: number;
  adx14: number;
  volume: number;
  relativeVolume: number;
  sessionHigh: number;
  sessionLow: number;
  recentHigh: number;
  recentLow: number;
  vwap: number;
  distanceFromEma9Percent: number;
  distanceFromEma21Percent: number;
  distanceFromVwapPercent: number;
  atrExtension: number;
};

export type WinStructure = {
  higherHigh: boolean;
  higherLow: boolean;
  lowerHigh: boolean;
  lowerLow: boolean;
  consolidation: boolean;
};

export type WinFlow = {
  available: boolean;
  buyAggression: number | null;
  sellAggression: number | null;
  delta: number | null;
  accumulated: number | null;
};

export type WinTradePlan = {
  suggestedEntry: number | null;
  technicalStop: number | null;
  riskPoints: number | null;
  target1: number | null;
  target2: number | null;
  rewardRiskTarget1: number | null;
  rewardRiskTarget2: number | null;
};

export type WinAnalysis = {
  contract: string;
  analyzedAt: string;
  contextTimeframe: string;
  executionTimeframe: string;
  currentPrice: number;
  marketState: WinMarketState;
  signal: WinSignal;
  executionStatus: WinExecutionStatus;
  setupType: WinSetupType;
  tradeLocation: TradeLocation;
  scores: WinScores;
  technical5m: WinTechnicalSnapshot;
  technical1m: WinTechnicalSnapshot;
  structure: WinStructure;
  flow: WinFlow;
  tradePlan: WinTradePlan;
  reasons: string[];
};
