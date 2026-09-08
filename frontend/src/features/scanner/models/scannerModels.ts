export type MomentumStatus = 'QUALIFIED' | 'WATCH' | 'REJECTED';

export type EntryStatus =
  | 'BREAKOUT_READY'
  | 'PULLBACK_READY'
  | 'ENTRY_READY'
  | 'PULLBACK_CONFIRMED'
  | 'BREAKOUT_CONFIRMED'
  | 'PULLBACK_IN_PROGRESS'
  | 'WATCH'
  | 'WAIT_PULLBACK'
  | 'WAIT_BREAKOUT'
  | 'POTENTIAL_BREAKOUT'
  | 'OVEREXTENDED'
  | 'TREND_WEAKENING'
  | 'INVALIDATED'
  | 'NO_ENTRY_SETUP';

export type EntrySetupType = 'PULLBACK' | 'BREAKOUT' | 'NONE';
export type ConfirmationStrength = 'NONE' | 'MODERATE' | 'STRONG';
export type RankingMode = 'MOMENTUM' | 'ENTRY' | 'BREAKOUT' | 'PULLBACK';
export type Trend = 'UPTREND' | 'DOWNTREND' | 'SIDEWAYS';
export type CandleStatus = 'CLOSED' | 'INTRADAY_PARTIAL';

export type Candle = {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  status: CandleStatus;
  fetchedAt?: string | null;
};

export type TechnicalAnalysis = {
  ticker: string;
  analysisDate: string;
  timeframe: string;
  lastClose: number;
  sma9: number;
  sma21: number;
  ema9: number;
  ema21: number;
  rsi9: number;
  atr14: number;
  adx14: number;
  averageVolume20: number;
  averageFinancialVolume20: number;
  relativeVolume20: number;
  distanceFromSma21Percent: number;
  return5DaysPercent: number;
  return20DaysPercent: number;
  distanceFromEma9Percent: number;
  distanceFromEma21Percent: number;
  highestHigh5: number;
  highestHigh10: number;
  highestHigh20: number;
  lowestLow5: number;
  lowestLow10: number;
  lowestLow20: number;
  recentHigh: number;
  recentHighDate?: string | null;
  recentLow: number;
  recentLowDate?: string | null;
  candlesSinceRecentHigh: number;
  pullbackDepthPercent: number;
  previousCandleHigh: number;
  previousCandleLow: number;
  previousCandleClose: number;
  movingAverageSlope9: number;
  movingAverageSlope21: number;
  consolidationWidthPercent: number;
  bullishVolumeAverage: number;
  bearishVolumeAverage: number;
  latestClosedCandleVolume: number;
  previousClosedCandleVolume: number;
  trend: Trend;
};

export type TechnicalChecks = {
  closeAboveSma21: boolean;
  sma9AboveSma21: boolean;
  rsiInsideRange: boolean;
  adxAccepted: boolean;
  liquidityAccepted: boolean;
  historyAccepted: boolean;
};

export type EntryChecks = {
  trendQualified: boolean;
  priceAboveSma21: boolean;
  ema9AboveEma21: boolean;
  adxAccepted: boolean;
  recentHighDetected: boolean;
  pullbackDepthAccepted: boolean;
  touchedShortAverage: boolean;
  sma21Preserved: boolean;
  rsiEntryRangeAccepted: boolean;
  closedAbovePreviousHigh: boolean;
  latestCloseAbovePreviousClose: boolean;
  latestCloseAboveEma9: boolean;
  bullishConfirmationCandle: boolean;
  previousLowPreserved: boolean;
  confirmationVolumeAccepted: boolean;
  breakoutAboveRecentHigh: boolean;
  breakoutVolumeAccepted: boolean;
  liquidityAccepted: boolean;
  breakoutEligible: boolean;
  latestCloseAboveEma21: boolean;
  breakoutRsiAccepted: boolean;
  breakoutAdxAccepted: boolean;
  breakoutExtensionAccepted: boolean;
  consolidationDetected: boolean;
  overextendedFromEma21: boolean;
  trendWeakening: boolean;
  ema9SlopeNegative: boolean;
  latestCloseBelowSma21: boolean;
  adxWeakening: boolean;
  latestSwingLowBelowPrevious: boolean;
  movingAveragesConverging: boolean;
  higherLowDetected: boolean;
  sufficientEntryHistory: boolean;
};

export type EntryScoreBreakdown = {
  trend: number;
  pullbackDepth: number;
  averageTouch: number;
  sma21Preserved: number;
  rsi: number;
  confirmationCandle: number;
  higherLow: number;
  volume: number;
  distance: number;
  breakout: number;
  relativeVolume: number;
  adx: number;
  movingAverageSlope: number;
  extension: number;
  recentReturn: number;
  total: number;
};

export type EntryAnalysis = {
  status: EntryStatus;
  setupType: EntrySetupType;
  confirmationStrength: ConfirmationStrength;
  score: number;
  scoreBreakdown: EntryScoreBreakdown;
  checks: EntryChecks;
  reasons: string[];
  recentHigh: number;
  recentLow: number;
  pullbackDepthPercent: number;
  distanceFromEma9Percent: number;
  distanceFromEma21Percent: number;
  candlesSinceRecentHigh: number;
  consolidationWidthPercent: number;
  nearestSupport: number;
  nearestResistance: number;
  recentHighBeforeLatest: number;
  breakoutPercentAboveResistance: number;
  breakoutConfirmed: boolean;
  pullbackDetected: boolean;
  pullbackConfirmed: boolean;
  overextended: boolean;
  sideways: boolean;
};

export type ScannerResult = {
  ticker: string;
  analysisDate: string;
  timeframe: string;
  lastClose: number;
  currentPrice: number;
  dataStatus: CandleStatus;
  currentPartialCandle?: Candle | null;
  marketDataUpdatedAt?: string | null;
  score: number;
  status: MomentumStatus;
  trend: Trend;
  technicalAnalysis: TechnicalAnalysis;
  entryAnalysis?: EntryAnalysis | null;
  checks: TechnicalChecks;
  reasons: string[];
};

export type ScanFailure = {
  ticker: string;
  errorCode: string;
  message: string;
};

export type MarketScanRequest = {
  minimumAverageFinancialVolume?: number;
  minimumScore?: number;
  minimumMomentumScore?: number;
  minimumEntryScore?: number;
  entryStatuses?: EntryStatus[];
  rankingMode?: RankingMode;
  limit?: number;
};

export type MarketScanResponse = {
  scannedAt: string;
  strategy: 'MOMENTUM';
  discoveredCount: number;
  eligibleCount: number;
  analyzedCount: number;
  qualifiedCount: number;
  momentumQualifiedCount: number;
  momentumWatchCount: number;
  breakoutEligibleCount: number;
  breakoutReadyCount: number;
  pullbackReadyCount: number;
  returnedCount: number;
  totalExecutionDurationMillis: number;
  averageAnalysisDurationMillis: number;
  providerRequestCount: number;
  results: ScannerResult[];
  failures: ScanFailure[];
};
