import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { createReplayTradeStore } from '../src/features/replay/api/replayTradeStore.ts';
import { createReplayOccurrenceStore } from '../src/features/replay/api/replayOccurrenceStore.ts';
import { createReplaySetupStore, isReplaySetupType } from '../src/features/replay/api/replaySetupStore.ts';
import { monitoringFlowSchema } from '../src/features/win/api/mt5LiveConnection.ts';

const delta = { timeMsc: 1000, windowMillis: 500, buyVolume: 10, sellVolume: 4,
  ambiguousVolume: 2, delta: 6, cumulativeDelta: 20, events: 3, processedTicks: 50,
  quality: 'GOOD', complete: true, cumulativeComplete: true };

function connection() {
  const source = readFileSync(new URL('../src/features/replay/api/replayConnection.ts', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  });
  const exports = {};
  const mocks = {
    './replayTradeStore': { createReplayTradeStore },
    './replayOccurrenceStore': { createReplayOccurrenceStore },
    './replaySetupStore': { createReplaySetupStore, isReplaySetupType },
    '@/features/win/models/mt5Intrabar': { M5_SECONDS: 300 },
  };
  runInNewContext(outputText, { exports, require: name => mocks[name], URL, WebSocket: { OPEN: 1, CONNECTING: 0 } });
  const socket = { readyState: 1, send() {}, close() {} };
  const errors = [], values = [], candles = [];
  const controller = exports.connectReplay('test', 'http://localhost', () => {}, error => errors.push(error), () => socket);
  controller.attachChart({ setHistory() {}, updateLast: c => candles.push(c), onVolumeDelta: v => values.push(v) });
  const message = payload => socket.onmessage({ data: JSON.stringify(payload) });
  return { socket, controller, errors, values, candles, message };
}

test('delta arrives independently of candle frames and is replayed to a late subscriber', () => {
  const h = connection();
  h.message({ type: 'FLOW', timeMsc: 1000, status: 'PLAYING', volumeDelta: delta });
  assert.equal(h.controller.getVolumeDelta().delta, 6);
  assert.equal(h.candles.length, 0);
  h.message({ type: 'FLOW', timeMsc: 1000, volumeDelta: { ...delta, processedTicks: 51, buyVolume: 12, delta: 8 } });
  h.message({ type: 'MARKET_STATE', volumeDelta: delta }); // Coalesced older state must not roll back memory.
  assert.equal(h.controller.getVolumeDelta().delta, 8);
  const late = [];
  h.controller.attachChart({ setHistory() {}, updateLast() {}, onVolumeDelta: v => late.push(v) });
  assert.equal(late.at(-1).processedTicks, 51);
  h.controller.close();
  assert.equal(h.controller.getVolumeDelta(), null);
  assert.equal(late.at(-1), null);
});

test('legacy messages remain compatible and invalid or unsafe deltas are not exposed', () => {
  const h = connection();
  h.message({ type: 'CLOCK', timeMsc: 1000, status: 'READY' });
  assert.equal(h.controller.getVolumeDelta(), null);
  for (const invalid of [{ ...delta, delta: 999 }, { ...delta, buyVolume: 2 ** 63 }, { ...delta, delta: 0.5 }]) {
    h.message({ type: 'FLOW', volumeDelta: invalid });
  }
  assert.equal(h.errors.length, 3);
  assert.equal(h.controller.getVolumeDelta(), null);
  h.controller.close();
});

test('live FLOW schema preserves the same optional tracker result without losing neutral volume', () => {
  const flow = { type: 'FLOW', symbol: 'WINZ26', timeMsc: 1000, lastTimeMsc: 1000,
    last: 200000, volumeSource: 'MT5_VOLUME_REAL', volumeDelta: delta,
    windows: [5, 10, 30].map(seconds => ({ seconds, fromTimeMsc: 1000-seconds*1000, toTimeMsc: 1000,
      events: 3, buyVolume: 10, sellVolume: 4, unknownVolume: 2, delta: 6,
      buyPercent: 62.5, sellPercent: 25, unknownPercent: 12.5, lastDisplacement: 0,
      quality: 'GOOD', alert: null })) };
  assert.deepEqual(monitoringFlowSchema.parse(flow).volumeDelta, delta);
  delete flow.volumeDelta;
  assert.equal(monitoringFlowSchema.parse(flow).volumeDelta, undefined);
});
