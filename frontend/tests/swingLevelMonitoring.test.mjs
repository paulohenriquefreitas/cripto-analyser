import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSwingLevelMonitoring } from '../src/features/win/models/swingLevelMonitoring.ts';
import { createSwingPivotTracker } from '../src/features/win/models/mt5SwingPivots.ts';
const top = { type: 'HIGH', time: 600, price: 1000, confirmedAt: 1500 };
const bottom = { type: 'LOW', time: 900, price: 850, confirmedAt: 1800 };
const setup = () => { const monitor = createSwingLevelMonitoring(); monitor.addConfirmed([top]); return monitor; };
const first = monitor => monitor.getSnapshot().levels[0];

test('approach activates at exactly 100 points and uses absolute LAST distance', () => {
  const monitor = setup();
  monitor.receiveLast(899, 1); assert.equal(first(monitor).state, 'WAITING');
  monitor.receiveLast(900, 2); assert.equal(first(monitor).state, 'MONITORING');
  assert.equal(first(monitor).distance, 100);
  monitor.receiveLast(970.5, 3); assert.equal(first(monitor).distance, 29.5);
});
test('hysteresis preserves monitoring through touch/break and releases only beyond 150', () => {
  const monitor = setup();
  monitor.receiveLast(900, 1);
  for (const [time, price] of [1000, 1050, 1101, 1150].entries()) {
    monitor.receiveLast(price, time + 2); assert.equal(first(monitor).state, 'MONITORING');
  }
  monitor.receiveLast(1150.01, 6); assert.equal(first(monitor).state, 'WAITING');
  monitor.receiveLast(1120, 7); assert.equal(first(monitor).state, 'WAITING');
  monitor.receiveLast(1100, 8); assert.equal(first(monitor).state, 'MONITORING');
  monitor.receiveLast(849, 9); assert.equal(first(monitor).state, 'WAITING');
});
test('multiple levels including non-nearest pivots keep independent states on both sides', () => {
  const monitor = setup(); monitor.addConfirmed([bottom]);
  monitor.receiveLast(950, 1);
  assert.deepEqual(monitor.getSnapshot().levels.map(level => [level.type, level.distance, level.state]),
    [['TOP', 50, 'MONITORING'], ['BOTTOM', 100, 'MONITORING']]);
  monitor.receiveLast(1010, 2);
  assert.deepEqual(monitor.getSnapshot().levels.map(level => level.state), ['MONITORING', 'WAITING']);
});
test('configuration validates hysteresis and reevaluates LAST without resetting per-level states', () => {
  const monitor = setup(); monitor.receiveLast(930, 1);
  monitor.configure({ activation: 50, deactivation: 60 });
  assert.equal(first(monitor).state, 'WAITING');
  monitor.receiveLast(950, 2); assert.equal(first(monitor).state, 'MONITORING');
  monitor.receiveLast(940, 3); assert.equal(first(monitor).state, 'MONITORING');
  for (const config of [{ activation: -1, deactivation: 150 }, { activation: 100, deactivation: 100 },
    { activation: 151, deactivation: 150 }, { activation: NaN, deactivation: 150 }]) assert.throws(() => monitor.configure(config));
});
test('invalid/stale ticks and duplicate pivots cannot change a confirmed price or state', () => {
  const monitor = setup(); monitor.receiveLast(950, 100);
  monitor.receiveLast(1500, 99); monitor.receiveLast(NaN, 101); monitor.receiveLast(0, 102);
  monitor.addConfirmed([{ ...top, price: 2000 }]);
  assert.equal(first(monitor).price, 1000); assert.equal(first(monitor).distance, 50);
  monitor.reset(); assert.equal(monitor.getSnapshot().last, null); assert.deepEqual(monitor.getSnapshot().levels, []);
});
test('only confirmed pivots enter monitoring; broken drawing levels remain monitored', () => {
  const tracker = createSwingPivotTracker(); const monitor = createSwingLevelMonitoring();
  const bars = [900, 950, 1000, 960, 970].map((high, index) => ({ time: index * 300, high, low: 800, close: 900 }));
  tracker.setHistory(bars); monitor.addConfirmed(tracker.getConfirmedLevels());
  assert.deepEqual(monitor.getSnapshot().levels, []);
  tracker.updateLast({ time: 1500, high: 1100, low: 800, close: 1050 });
  monitor.addConfirmed(tracker.getConfirmedLevels()); monitor.receiveLast(950, 1500000);
  tracker.updateLast({ time: 1800, high: 1100, low: 800, close: 1050 });
  assert.deepEqual(tracker.getLevels(), []);
  monitor.addConfirmed(tracker.getConfirmedLevels()); monitor.receiveLast(1050, 1800000);
  assert.equal(first(monitor).state, 'MONITORING');
  monitor.receiveLast(1151, 1800001); assert.equal(first(monitor).state, 'WAITING');
});
