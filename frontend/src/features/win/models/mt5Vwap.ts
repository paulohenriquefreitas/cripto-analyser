import type { Mt5Candle } from '@/features/win/api/mt5Api';

/** Separate chart series per backend session: never connect different trading dates. */
export function vwapSegments(candles: Mt5Candle[]) {
  const groups = new Map<string, { time: number; value: number }[]>();
  for (const candle of candles) {
    if (!candle.vwapSession || candle.vwap == null || !Number.isFinite(candle.vwap)) continue;
    let points = groups.get(candle.vwapSession);
    if (!points) { points = []; groups.set(candle.vwapSession, points); }
    points.push({ time: candle.time, value: candle.vwap });
  }
  return [...groups.entries()].map(([session, points]) => ({ session, points }));
}
