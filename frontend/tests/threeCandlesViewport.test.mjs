import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import * as groups from '../src/features/threecandles/models/m5DisplayGroups.ts';

function load(path, require) {
  const exports = {};
  const { outputText } = ts.transpileModule(readFileSync(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  });
  runInNewContext(outputText, { exports, require, Intl, Date });
  return exports;
}
const controller = () => load('../src/features/threecandles/models/candleChartController.ts', () => groups).createCandleChartController();

function chartHost(optIn = true) {
  const c = controller(), ranges = [], prices = [], seriesOptions = [], primitives = [];
  let options, cleanup, data = [];
  const series = { applyOptions: o => seriesOptions.push(o), setData: d => { data = d; }, update: b => { data = [...data, b]; },
    attachPrimitive: p => primitives.push(p) };
  const chart = { addSeries: () => series, remove() {}, subscribeCrosshairMove() {},
    priceScale: () => ({ applyOptions: o => prices.push(o) }),
    timeScale: () => ({ applyOptions() {}, scrollToPosition() {}, setVisibleLogicalRange: r => ranges.push(r) }) };
  class Primitive { setLevels(levels) { this.levels = levels; } }
  const mocks = {
    react: { useRef: () => ({ current: {} }), useState: () => [false, failed => assert.equal(failed, false)], useEffect: fn => { cleanup = fn(); } },
    'react/jsx-runtime': { jsx() {}, jsxs() {} },
    '@mui/material': { useTheme: () => ({ palette: { background: {}, text: {}, success: {}, error: {}, warning: {} } }) },
    'lightweight-charts': { ColorType: {}, createChart: (_, o) => { options = o; return chart; }, createSeriesMarkers: () => ({ setMarkers() {} }) },
  };
  const { Mt5CandlestickChart } = load('../src/features/win/components/Mt5CandlestickChart.tsx', name => {
    if (mocks[name]) return mocks[name];
    if (name.endsWith('SwingPivotPrimitive')) return { SwingPivotPrimitive: Primitive };
    if (name.endsWith('mt5SwingPivots')) return { createSwingPivotTracker: () => ({ getLevels: () => [] }) };
    return {};
  });
  Mt5CandlestickChart({ controller: c, candlesOnly: true, structuralLevels: true, visibleCandleViewport: optIn });
  return { c, ranges, prices, seriesOptions, primitives, get options() { return options; }, get data() { return data; }, close: () => cleanup() };
}
const raw = JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/grouping/replay.json', import.meta.url), 'utf8'));
const prefix = time => ({ ...raw, availableAtMsc: time, candles: raw.candles.filter(b => b.availableAtMsc <= time),
  structure: { ...raw.structure, levels: raw.structure.levels.filter(l => l.confirmedAtMsc <= time) } });
const opening = Date.parse('2026-10-16T09:00:00Z');

test('native viewport uses released visible candles and margins, while offscreen structural levels stay attached', () => {
  const h = chartHost(); h.c.setVisibility(true, true); h.c.apply(prefix(opening));
  assert.equal(h.options.rightPriceScale.autoScale, true);
  assert.deepEqual({ ...h.options.rightPriceScale.scaleMargins }, { top: 0.1, bottom: 0.1 });
  assert.equal(h.seriesOptions.some(o => o.autoscaleInfoProvider), false, 'H1 must not expand native autoscale');
  const range = h.ranges.at(-1);
  assert.equal(range.to - range.from, 44);
  assert.equal(range.from, h.data.length - 40);
  assert.equal(h.data.length, prefix(opening).candles.length, 'only released history reaches the chart');
  assert.equal(h.primitives[1].levels.length, 9, 'all H1 remain even outside visible price range');
  const known = h.data.slice(range.from);
  const low = Math.min(...known.map(b => b.low)), high = Math.max(...known.map(b => b.high));
  assert.ok(h.primitives[1].levels.some(l => l.price < low || l.price > high));
  h.close();
});

test('Play, visibility and grouping preserve manual scaling; explicit fit, reset and date change restore it', () => {
  const h = chartHost(); h.c.setVisibility(true, true); h.c.apply(prefix(opening));
  const fits = h.prices.length;
  h.prices.push({ autoScale: false }); // native user axis gesture
  h.ranges.push({ from: 1, to: 9 }); // native user pan/zoom
  for (let i = 1; i <= 12; i++) h.c.apply(prefix(opening + i * 300000));
  h.c.setVisibility(false, true); h.c.setM5Grouping(50); h.c.setVisibility(true, true);
  assert.equal(h.prices.length, fits + 1);
  assert.deepEqual(h.ranges.at(-1), { from: 1, to: 9 });
  h.c.fitChart(); assert.equal(h.prices.at(-1).autoScale, true);
  assert.equal(h.ranges.at(-1).from, h.data.length - 40);
  h.c.reset(); h.c.apply(prefix(opening)); assert.equal(h.prices.length, fits + 3);
  const atReset = h.data.length;
  assert.equal(h.ranges.at(-1).from, atReset - 40);
  h.c.reset(); h.c.apply({ ...prefix(opening), id: 'other-date', structure: { ...prefix(opening).structure,
    preparation: { ...raw.structure.preparation, sessionOpenMsc: opening + 86400000 } } });
  assert.equal(h.prices.length, fits + 4);
  assert.equal(h.data.length, atReset);
  h.close();
});

test('other chart consumers keep their existing structural autoscale and focus behavior', () => {
  const h = chartHost(false); h.c.apply(prefix(opening));
  assert.equal(h.options.rightPriceScale, undefined);
  assert.equal(h.prices.length, 0);
  const provider = h.seriesOptions.findLast(o => o.autoscaleInfoProvider).autoscaleInfoProvider;
  const native = { priceRange: { minValue: 208000, maxValue: 209000 } };
  assert.ok(provider(() => native).priceRange.maxValue > 209000);
  assert.equal(h.ranges.at(-1).to - h.ranges.at(-1).from, 80);
  h.close();
});
