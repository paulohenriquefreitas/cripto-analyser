import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMt5Intrabar, updateLastCandle, m5Bucket } from '../src/features/win/models/mt5Intrabar.ts';

const candle = { time: 1790005500, open: 100, high: 110, low: 90, close: 105, tickVolume: 12, realVolume: 34 };
const tick = (last, seconds = candle.time + 1, millis = 0) => ({
  type: 'tick', symbol: 'WINV26', time: seconds, timeMsc: seconds * 1000 + millis,
  last, bid: 999, ask: 1000, volume: 9999,
});
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function setup(load) {
  const controller = createMt5Intrabar(load);
  const histories = [], updates = [];
  controller.attachChart({ setHistory: rows => histories.push(rows), updateLast: row => updates.push(row) });
  controller.start();
  return { controller, histories, updates };
}

test('same bucket updates close, expands extrema, preserves open/time/volumes and original object', () => {
  for (const [last, high, low] of [[108, 110, 90], [115, 115, 90], [85, 110, 85]]) {
    const result = updateLastCandle(candle, tick(last));
    assert.equal(result.kind, 'updated');
    assert.deepEqual(result.candle, { ...candle, high, low, close: last });
  }
  let current = candle;
  for (const last of [115, 85, 105]) current = updateLastCandle(current, tick(last)).candle;
  assert.deepEqual(current, { ...candle, high: 115, low: 85 });
  assert.equal(candle.close, 105);
});
test('raw millisecond boundary triggers resync; older buckets and invalid prices are ignored', () => {
  assert.equal(m5Bucket(candle.time + 299.999), candle.time);
  assert.equal(updateLastCandle(candle, tick(108, candle.time + 299, 999)).kind, 'updated');
  assert.equal(updateLastCandle(candle, tick(108, candle.time + 300)).kind, 'resync');
  assert.equal(updateLastCandle(candle, tick(108, candle.time - 1)).kind, 'ignored');
  assert.equal(updateLastCandle(candle, tick(0)).kind, 'ignored');
  assert.equal(updateLastCandle(candle, tick(NaN)).kind, 'ignored');
  assert.equal(updateLastCandle(candle, { ...tick(108), time: 1 }).kind, 'ignored');
});
test('tick before initial REST is replayed; same bucket does not fetch or replace history', async () => {
  const initial = deferred(); let calls = 0;
  const { controller, histories, updates } = setup(() => { calls++; return initial.promise; });
  controller.receiveTick(tick(115));
  assert.equal(updates.length, 0);
  initial.resolve([candle]); await flush();
  assert.equal(updates.at(-1).close, 115);
  controller.receiveTick(tick(85, candle.time + 2));
  assert.equal(updates.at(-1).open, 100);
  assert.equal(updates.at(-1).high, 115);
  assert.equal(updates.at(-1).low, 85);
  assert.equal(histories.length, 1);
  assert.equal(calls, 1);
  controller.stop();
});
test('one rollover fetch, no change to previous candle, latest tick during resync is replayed', async () => {
  const pending = deferred(); let calls = 0;
  const { controller, histories, updates } = setup(() => ++calls === 1 ? Promise.resolve([candle]) : pending.promise);
  await flush();
  controller.receiveTick(tick(115, candle.time + 300));
  controller.receiveTick(tick(120, candle.time + 301));
  controller.receiveTick(tick(125, candle.time + 302));
  assert.equal(calls, 2);
  assert.equal(updates.length, 0);
  const official = { ...candle, time: candle.time + 300, open: 112, high: 122, low: 111, close: 114 };
  pending.resolve([candle, official]); await flush();
  assert.equal(histories.length, 2);
  assert.deepEqual(updates.at(-1), { ...official, high: 125, close: 125 });
  assert.equal(calls, 2);
  controller.stop();
});
test('failed rollover keeps history, retries after 5 seconds, no requests from intervening ticks', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.mock.method(console, 'error', () => {});
  let calls = 0;
  const official = { ...candle, time: candle.time + 300 };
  const { controller, histories, updates } = setup(() => {
    calls++; return calls === 1 ? Promise.resolve([candle])
      : calls === 2 ? Promise.reject(new Error('offline')) : Promise.resolve([official]);
  });
  await flush();
  controller.receiveTick(tick(115, candle.time + 300)); await flush();
  assert.match(controller.getSnapshot().error, /preservado/);
  assert.equal(controller.getSnapshot().count, 1);
  for (let i = 1; i < 20; i++) controller.receiveTick(tick(120, candle.time + 300 + i));
  t.mock.timers.tick(4999); assert.equal(calls, 2);
  t.mock.timers.tick(1); await flush();
  assert.equal(calls, 3);
  assert.equal(histories.length, 2);
  assert.equal(updates.at(-1).close, 120);
  assert.equal(controller.getSnapshot().error, null);
  controller.stop();
});
test('MT5 still returning previous bucket causes controlled retry, not a tight request loop', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let calls = 0;
  const { controller, updates } = setup(() => { calls++; return Promise.resolve([candle]); });
  await flush();
  controller.receiveTick(tick(120, candle.time + 300)); await flush();
  assert.equal(updates.length, 0);
  assert.equal(calls, 2);
  for (let i = 1; i < 10; i++) controller.receiveTick(tick(121, candle.time + 300 + i));
  assert.equal(calls, 2);
  assert.match(controller.getSnapshot().error, /oficial/);
  controller.stop(); t.mock.timers.tick(10000); await flush();
  assert.equal(calls, 2);
});
test('ignores out-of-order ticks and stale REST completion after cleanup', async () => {
  const pending = deferred(); let calls = 0; let signal;
  const { controller, histories, updates } = setup(s => {
    signal = s; return ++calls === 1 ? Promise.resolve([candle]) : pending.promise;
  });
  await flush();
  controller.receiveTick(tick(115, candle.time + 10));
  controller.receiveTick(tick(80, candle.time + 5));
  assert.equal(updates.at(-1).close, 115);
  controller.receiveTick(tick(120, candle.time + 300));
  controller.stop(); assert.equal(signal.aborted, true);
  pending.resolve([{ ...candle, time: candle.time + 300 }]); await flush();
  assert.equal(histories.length, 1);
});
test('React subscription does not notify on every intrabar tick', async () => {
  const { controller } = setup(() => Promise.resolve([candle])); await flush();
  let renders = 0; const unsubscribe = controller.subscribe(() => renders++);
  for (let i = 1; i < 50; i++) controller.receiveTick(tick(100 + i, candle.time + i));
  assert.equal(renders, 0);
  unsubscribe(); controller.stop();
});


test('SMA comes from backend only, matched to candle timestamp', () => {
  const baseline = { ...candle, sma9: 104 };
  const updated = updateLastCandle(baseline, { ...tick(115), sma9: 105.123456789, sma9Time: candle.time });
  assert.equal(updated.candle.sma9, 105.123456789);
  for (const payload of [{ sma9: null, sma9Time: null }, { sma9: 999, sma9Time: candle.time - 300 }]) {
    assert.equal(updateLastCandle(baseline, { ...tick(115), ...payload }).candle.sma9, 104);
  }
});

test('SMA received during rollover survives REST completion without extra requests', async () => {
  const pending = deferred(); let calls = 0;
  const { controller, updates } = setup(() => ++calls === 1 ? Promise.resolve([{ ...candle, sma9: 104 }]) : pending.promise);
  await flush();
  const newTick = { ...tick(120, candle.time + 301), sma9: null, sma9Time: null };
  controller.receiveTick(newTick);
  controller.receiveTick({ ...newTick, sma9: 107.777777, sma9Time: candle.time + 300, sma21: 102.222222, sma21Time: candle.time + 300 });
  pending.resolve([{ ...candle, time: candle.time + 300, sma9: 105 }]);
  await flush();
  assert.equal(updates.at(-1).sma9, 107.777777);
  assert.equal(updates.at(-1).sma21, 102.222222);
  assert.equal(updates.at(-1).close, 120);
  assert.equal(calls, 2);
  controller.stop();
});


test('SMA21 uses only backend values for the current raw candle time, preserving SMA9', () => {
  const baseline = { ...candle, sma9: 104, sma21: 99 };
  const enriched = { ...tick(115), sma9: 105, sma9Time: candle.time, sma21: 101.123456789, sma21Time: candle.time };
  const result = updateLastCandle(baseline, enriched).candle;
  assert.equal(result.sma9, 105);
  assert.equal(result.sma21, 101.123456789);
  assert.equal(updateLastCandle(baseline, { ...enriched, sma21Time: candle.time - 300 }).candle.sma21, 99);
  assert.equal(updateLastCandle(baseline, { ...enriched, sma21: null }).candle.sma21, 99);
});
