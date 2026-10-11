import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSwingPivotTracker } from '../src/features/win/models/mt5SwingPivots.ts';
import { SwingPivotPrimitive } from '../src/features/win/components/SwingPivotPrimitive.ts';

const bar = (index, high, low = 0) => ({ time: index * 300, high, low, close: (high + low) / 2 });
const peak = [bar(0, 3, 2), bar(1, 4, 1), bar(2, 10, -5), bar(3, 5, 0), bar(4, 6, 1)];

test('waits for two closed candles on each side; the forming second right candle cannot confirm', () => {
  const tracker = createSwingPivotTracker();
  tracker.setHistory(peak);
  assert.deepEqual(tracker.getLevels(), []);
  tracker.updateLast(bar(4, 7, 0));
  assert.deepEqual(tracker.getLevels(), []);
  tracker.updateLast(bar(5, 9, -1));
  assert.deepEqual(tracker.getLevels(), [
    { type: 'HIGH', time: 600, price: 10, confirmedAt: 1500 },
    { type: 'LOW', time: 600, price: -5, confirmedAt: 1500 },
  ]);
});

test('uses wick extremes and strict comparisons; equal left or right extremes reject pivots', () => {
  for (const neighbor of [0, 1, 3, 4]) {
    const tracker = createSwingPivotTracker();
    const bars = peak.map((candle, index) => index === neighbor ? { ...candle, high: 10, low: -5 } : candle);
    tracker.setHistory([...bars, bar(5, 3)]);
    assert.deepEqual(tracker.getLevels(), []);
  }
});

test('forming candles may invalidate candidates, but later ticks and history corrections never repaint confirmed pivots', () => {
  const rejected = createSwingPivotTracker();
  rejected.setHistory(peak);
  rejected.updateLast(bar(4, 11, -6));
  rejected.updateLast(bar(5, 3));
  assert.deepEqual(rejected.getLevels(), []);
  const tracker = createSwingPivotTracker();
  const history = [...peak, bar(5, 3)];
  tracker.setHistory(history);
  const confirmed = tracker.getLevels();
  tracker.updateLast(bar(5, 100, -100));
  assert.deepEqual(tracker.getLevels(), confirmed);
  tracker.setHistory(history.map(candle => ({ ...candle, high: 100, low: -100 })));
  assert.deepEqual(tracker.getLevels(), confirmed);
  tracker.updateLast(bar(3, 200, -200));
  assert.deepEqual(tracker.getLevels(), confirmed);
});

test('history refreshes and rolling windows deduplicate and retain old unbroken highs and lows independently', () => {
  const history = Array.from({ length: 66 }, (_, index) => bar(index, index % 5 === 2 ? 100 + index : 5,
    index % 5 === 2 ? -100 - index : 0));
  const tracker = createSwingPivotTracker();
  tracker.setHistory(history.slice(0, 31));
  tracker.setHistory(history.slice(20));
  const levels = tracker.getLevels();
  const highs = levels.filter(level => level.type === 'HIGH');
  const lows = levels.filter(level => level.type === 'LOW');
  assert.equal(highs.length, 13);
  assert.equal(lows.length, 13);
  assert.equal(highs[0].time, 2 * 300);
  assert.equal(highs.at(-1).time, 62 * 300);
  tracker.setHistory(history.slice(20));
  assert.deepEqual(tracker.getLevels(), levels);
  tracker.setHistory([]);
  assert.deepEqual(tracker.getLevels(), []);
  tracker.setHistory([...peak, bar(5, 3)]);
  assert.equal(tracker.getLevels().length, 2);
});

test('historical loading and incremental candle closure produce identical confirmed levels', () => {
  const history = Array.from({ length: 80 }, (_, index) => bar(index, Math.sin(index) * 10 + 20, Math.cos(index) * 10));
  const batch = createSwingPivotTracker();
  batch.setHistory(history);
  const incremental = createSwingPivotTracker();
  history.forEach(candle => incremental.updateLast(candle));
  assert.deepEqual(incremental.getLevels(), batch.getLevels());
});

test('rays are red and dashed, start at the pivot wick, extend right, follow coordinates and reset', () => {
  const primitive = new SwingPivotPrimitive();
  let updates = 0;
  let zoom = 1;
  primitive.attached({
    chart: { timeScale: () => ({ timeToCoordinate: time => time === 0 ? null : time / 10 * zoom }) },
    series: { priceToCoordinate: price => 480 - price * zoom },
    requestUpdate: () => updates++,
  });
  const paths = [];
  let path;
  const context = { save() {}, restore() {}, setLineDash(dash) { this.dash = dash; },
    beginPath() { path = []; }, moveTo(x, y) { path.push([x, y]); }, lineTo(x, y) { path.push([x, y]); },
    stroke() { paths.push({ path, color: this.strokeStyle, dash: this.dash }); } };
  const paint = () => primitive.paneViews()[0].renderer().draw({
    useMediaCoordinateSpace: callback => callback({ context, mediaSize: { width: 800, height: 480 } }),
  });
  primitive.setLevels([{ type: 'HIGH', time: 600, price: 100, confirmedAt: 1500 }], 300);
  paint();
  assert.deepEqual(paths.pop(), { path: [[60, 380], [800, 380]], color: '#ff5252', dash: [6, 4] });
  zoom = 2; paint();
  assert.deepEqual(paths.pop().path, [[120, 280], [800, 280]]);
  primitive.setLevels([{ type: 'LOW', time: 0, price: 50, confirmedAt: 900 }], 300);
  paint();
  assert.deepEqual(paths.pop().path, [[0, 380], [800, 380]]);
  primitive.setLevels([], 300); paint();
  assert.deepEqual(paths, []);
  assert.equal(updates, 3);
  primitive.detached(); paint();
  assert.deepEqual(paths, []);
});


test('retires only a level broken by a closed candle; wick touches and forming breaks preserve context', () => {
  const tracker = createSwingPivotTracker();
  tracker.setHistory([...peak, { ...bar(5, 20, -20), close: 0 }]);
  const original = tracker.getLevels();
  tracker.updateLast({ ...bar(5, 20, -20), close: 11 });
  assert.deepEqual(tracker.getLevels(), original, 'forming break does not remove a level');
  tracker.updateLast({ ...bar(5, 20, -20), close: 0 });
  tracker.updateLast({ ...bar(6, 20, -20), close: 10 });
  assert.deepEqual(tracker.getLevels(), original, 'wick break with close inside preserves both levels');
  tracker.updateLast({ ...bar(7, 20, -20), close: 11 });
  assert.deepEqual(tracker.getLevels(), original, 'closing exactly on the top is not a break');
  tracker.updateLast({ ...bar(8, 20, -20), close: 0 });
  assert.deepEqual(tracker.getLevels(), original.filter(level => level.type === 'LOW'));
  tracker.updateLast({ ...bar(8, 20, -20), close: -6 });
  assert.equal(tracker.getLevels().length, 1, 'forming break below the bottom is ignored');
  tracker.updateLast({ ...bar(9, 20, -20), close: 0 });
  assert.deepEqual(tracker.getLevels(), []);
  tracker.setHistory([...peak, bar(5, 3)]);
  assert.deepEqual(tracker.getLevels(), [], 'old refresh cannot resurrect broken levels');
});
