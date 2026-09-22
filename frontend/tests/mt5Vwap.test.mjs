import { test } from 'node:test';
import assert from 'node:assert/strict';
import { vwapSegments } from '../src/features/win/models/mt5Vwap.ts';
import { updateLastCandle } from '../src/features/win/models/mt5Intrabar.ts';

test('separates backend sessions without a connecting segment or frontend calculation', () => {
  assert.deepEqual(vwapSegments([
    { time: 1, vwap: null, vwapSession: 'incomplete' },
    { time: 2, vwap: 10.123456789, vwapSession: 'a' },
    { time: 3, vwap: 12, vwapSession: 'a' },
    { time: 4, vwap: 100, vwapSession: 'b' },
  ]), [
    { session: 'a', points: [{ time: 2, value: 10.123456789 }, { time: 3, value: 12 }] },
    { session: 'b', points: [{ time: 4, value: 100 }] },
  ]);
  assert.deepEqual(vwapSegments([]), []);
});

test('ticks preserve official VWAP and volume while moving close', () => {
  const candle = { time: 300, open: 10, high: 12, low: 9, close: 11,
    realVolume: 50, tickVolume: 20, vwap: 10.5, vwapSession: 'a' };
  const result = updateLastCandle(candle, { time: 301, timeMsc: 301123, last: 20, volume: 9999 });
  assert.equal(result.candle.close, 20);
  assert.equal(result.candle.vwap, 10.5);
  assert.equal(result.candle.realVolume, 50);
});
