import { z } from 'zod';

import { httpClient } from '@/common/api/httpClient';

const candleSchema = z.object({
  time: z.number().int().safe(),
  open: z.number().finite().positive(),
  high: z.number().finite(),
  low: z.number().finite(),
  close: z.number().finite().positive(),
  tickVolume: z.number().int(),
  realVolume: z.number().int(),
  sma9: z.number().finite().nullable().optional(),
  sma21: z.number().finite().nullable().optional(),
  vwap: z.number().finite().nullable().optional(),
  vwapSession: z.string().optional(),
}).refine((candle) => candle.high >= candle.low);

export type Mt5Candle = z.infer<typeof candleSchema>;

export async function getMt5Candles(signal?: AbortSignal): Promise<Mt5Candle[]> {
  // Always calls the real endpoint, independently of VITE_USE_MOCK_API.
  const response = await httpClient.get<unknown>('/api/mt5/candles', { timeout: 70_000, signal });
  const candles = z.array(candleSchema).parse(response.data);
  if (candles.some((candle, i) => i > 0 && candle.time <= candles[i - 1].time)) {
    throw new Error('Candles fora de ordem temporal.');
  }
  return candles;
}
