export type MonitoringFlowWindow = {
  seconds: 5 | 10 | 30; fromTimeMsc: number; toTimeMsc: number; events: number;
  buyVolume: number; sellVolume: number; unknownVolume: number; delta: number;
  buyPercent: number | null; sellPercent: number | null; unknownPercent: number | null;
  lastDisplacement: number | null;
  quality: 'GOOD' | 'WARMING_UP' | 'DISCONNECTED' | 'INVALID_VOLUME' | 'NO_TRADES'
    | 'STALE' | 'HIGH_UNKNOWN' | 'PRICE_UNAVAILABLE' | 'UNVALIDATED_SYMBOL';
  alert: 'POSSIBLE_ABSORPTION' | null;
};
export type MonitoringFlowSnapshot = {
  type: 'FLOW'; symbol: string; timeMsc: number; lastTimeMsc: number | null;
  last: number | null; volumeSource: 'MT5_VOLUME_REAL'; windows: MonitoringFlowWindow[];
};
