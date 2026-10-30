import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatCandleTime, candleDisplayZone } from '../src/features/threecandles/models/candleTime.ts';

test('terminal 09:00 remains 09:00, without rewriting the raw timestamp', () => {
  const raw = Date.parse('2026-10-20T09:00:00Z');
  assert.match(formatCandleTime(raw, 'MT5_WALL_CLOCK'), /09:00:00/);
  assert.equal(candleDisplayZone('MT5_WALL_CLOCK'), 'UTC');
  assert.equal(raw, 1792486800000);
});

test('true UTC timestamps use Brasilia only when that convention is explicitly selected', () => {
  const utc = Date.parse('2026-10-20T12:00:00Z');
  assert.match(formatCandleTime(utc, 'UTC_INSTANT'), /09:00:00/);
  assert.equal(candleDisplayZone('UTC_INSTANT'), 'America/Sao_Paulo');
  assert.match(formatCandleTime(Date.parse('2026-10-20T01:00:00Z'), 'MT5_WALL_CLOCK'), /20\/10\/2026/);
  assert.match(formatCandleTime(Date.parse('2026-10-20T01:00:00Z'), 'UTC_INSTANT'), /19\/10\/2026/);
});
