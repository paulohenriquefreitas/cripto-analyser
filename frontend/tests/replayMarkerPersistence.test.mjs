import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

// Execute the real connection, stores, manager and chart component. Only the
// React host, socket transport and graphics API are replaced with test doubles.
function harness() {
  const root = fileURLToPath(new URL('../src/', import.meta.url));
  const cache = new Map();
  let visible = [];
  let cleanup;
  let crosshair;
  const refs = [];
  const lines = new Map();
  const createdSeries = [];
  const rendered = [];
  let primitive;
  const bars = new Map();
  let zoom = 1;
  const series = {
    setData(data) { bars.clear(); data.forEach(bar => bars.set(bar.time, bar)); },
    update(bar) { bars.set(bar.time, bar); },
    dataByIndex(time) { return bars.get(time); },
    priceToCoordinate(price) { return 240 - (price - 182000) / 20; },
    attachPrimitive(p) { primitive = p; p.attached({ chart, series, requestUpdate() {} }); },
  };
  const chart = {
    addSeries: (_kind, options) => {
      createdSeries.push(structuredClone(options));
      if (!options.title) return series;
      const line = { data: [], setData(data) { this.data = structuredClone(data); }, update() {} };
      lines.set(options.title, line);
      return line;
    }, removeSeries() {}, remove() {},
    subscribeCrosshairMove: callback => { crosshair = callback; },
    timeScale: () => ({ applyOptions() {}, scrollToPosition() {}, timeToIndex: time => bars.has(time) ? time : null, timeToCoordinate: time => (time % 86400 - 36000) / 30 * zoom + 40 }),
  };
  const mocks = {
    '@mui/material': {
      useTheme: () => ({ palette: { background: {}, text: {}, success: {}, error: {}, warning: {} } }),
    },
    react: {
      useRef: () => { const ref = { current: {} }; refs.push(ref); return ref; },
      useState: () => [false, failed => assert.equal(failed, false, 'chart initialization failed')],
      useEffect: effect => { cleanup = effect(); },
    },
    'react/jsx-runtime': {
      jsx(type, props) { const node = { type, props }; rendered.push(node); return node; },
      jsxs(type, props) { const node = { type, props }; rendered.push(node); return node; },
    },
    'lightweight-charts': {
      ColorType: {}, createChart: () => chart,
      createSeriesMarkers: () => ({ setMarkers: markers => { visible = structuredClone(markers); } }),
    },
  };
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const exports = {};
    const { outputText } = ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX,
      },
    });
    const require = name => {
      if (mocks[name]) return mocks[name];
      const base = name.startsWith('@/') ? resolve(root, name.slice(2)) : resolve(dirname(file), name);
      return load(base + (existsSync(base + '.ts') ? '.ts' : '.tsx'));
    };
    runInNewContext(outputText, {
      exports, require, URL, AbortController, WebSocket: { OPEN: 1, CONNECTING: 0 },
    }, { filename: file });
    cache.set(file, exports);
    return exports;
  }
  const { connectReplay } = load(resolve(root, 'features/replay/api/replayConnection.ts'));
  const { createReplaySessionManager } = load(resolve(root, 'features/replay/api/replaySessionManager.ts'));
  const { Mt5CandlestickChart } = load(resolve(root, 'features/win/components/Mt5CandlestickChart.tsx'));
  const sockets = [];
  const manager = createReplaySessionManager({
    createSession: async () => ({ replayId: String(sockets.length), currentTimeMsc: 0 }),
    connect: (id, url, clock, error) => connectReplay(id, 'http://localhost', clock, error, () => {
      const socket = { close() {} };
      sockets.push(socket);
      return socket;
    }),
  });
  return {
    manager,
    mount: (setup91Only = true) => Mt5CandlestickChart({ controller: manager.getSnapshot().controller, setup91Only }),
    unmount: () => cleanup(),
    visible: () => visible.map(marker => ({ ...marker, text: primitive?.markers.get(marker.id)?.text ?? '' })),
    rawMarkers: () => visible,
    primitive: () => primitive,
    zoom: value => { zoom = value; },
    paint: () => {
      const texts = [], paths = [], rectangles = [];
      let path = [], dash = [], color;
      const ctx = {
        save() {}, restore() {}, beginPath() { path = []; },
        moveTo(x, y) { path.push([x, y]); }, lineTo(x, y) { path.push([x, y]); },
        stroke() { paths.push({ path, dash, color }); }, arc() {}, fill() {},
        setLineDash(value) { dash = value; },
        set strokeStyle(value) { color = value; }, get strokeStyle() { return color; },
        measureText(text) { return { width: text.length * 6 }; },
        fillRect(x, y, width, height) { rectangles.push({ x, y, width, height }); },
        fillText(text, x, y) { texts.push({ text, x, y, color: this.fillStyle }); },
      };
      primitive?.paneViews()[0].renderer().draw({ useMediaCoordinateSpace(draw) {
        draw({ context: ctx, mediaSize: { width: 800, height: 480 } });
      } });
      return { texts, paths, rectangles };
    },
    createdSeries: () => createdSeries,
    rendered: () => rendered,
    ema: () => lines.get('EMA9 (closed)').data,
    hover: time => { crosshair({ time }); return refs.at(-1).current.textContent; },
    send: message => sockets.at(-1).onmessage({ data: JSON.stringify(message) }),
  };
}

test('Replay rejects all six legacy Pullback09 events, including after remount', async () => {
  const h = harness();
  await h.manager.load('2026-09-24'); h.mount();
  for (const [i, setupType] of ['CRZ09_UP', 'RJ09_UP', 'PULLB09_UP',
    'CRZ09_DOWN', 'RJ09_DOWN', 'PULLB09_DOWN'].entries()) {
    const time = 6000 + i * 300;
    h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
      candle: { time, open: 100, high: 110, low: 90, close: 105 } });
    h.send({ type: 'REPLAY_SETUP_EVENT', eventId: setupType, setupType,
      timeMsc: time * 1000, candleTimeMsc: (time - 300) * 1000 });
    assert.deepEqual(h.visible(), []);
  }
  h.unmount(); h.mount(); assert.deepEqual(h.visible(), []);
  h.manager.close(); h.unmount();
});

for (const side of ['BUY', 'SELL']) {
  test(`Setup91 ${side}: source/event anchors, details, closed EMA, persistence and explicit reset`, async () => {
    const h = harness();
    await h.manager.load('2026-09-24');
    h.mount();
    const market = (time, closedEma9) => h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
      candle: { time, open: 100, high: 120, low: 80, close: 110 }, closedEma9 });
    market(6000); market(6300);
    assert.equal(h.ema().length, 0, 'no client EMA or developing candle EMA');
    assert.equal(h.visible().length, 0, 'candles alone never calculate Setup91');
    // Deliberately arbitrary backend EMA values: frontend must preserve, never recompute them.
    const signal = { side, candle: { symbol: 'WINV26', bucketStartTimeMsc: 6300000,
      open: 100, high: 120, low: 80, close: 110 }, ema9: 987.125,
      previousFiveEma9: [105, 104, 103, 102, 101], previousFiveDirection: 'DOWN',
      previousSlope: -1, currentSlope: 886.125 };
    const event = (state, timeMsc) => ({ type: 'REPLAY_SETUP_EVENT', eventId: `${side}-${state}`,
      setupType: `SETUP91_${side}_${state}`, candleTimeMsc: 6300000, timeMsc,
      signal, sequence: 14, breakoutPrice: state === 'TRIGGERED' ? (side === 'BUY' ? 121 : 79) : null });
    market(6600, { time: 6300, value: signal.ema9 });
    assert.deepEqual(h.ema(), [{ time: 6300, value: 987.125 }]);
    h.send(event('ARMED', 6600000));
    assert.deepEqual(h.visible(), [], 'ARMED is available only in hover details');
    const details = h.hover(6300);
    for (const field of [`SETUP91_${side}_ARMED`, 'Event time: 1970-01-01T01:50:00.000Z',
      'Signal candle: 1970-01-01T01:45:00.000Z', 'OHLC: 100 / 120 / 80 / 110',
      'EMA9 at signal: 987.125', '105, 104, 103, 102, 101', 'classification: DOWN',
      'Previous EMA slope: -1', 'Current EMA slope: 886.125']) assert.ok(details.includes(field), field);
    market(6900, { time: 6600, value: 988 });
    h.send(event('TRIGGERED', 6900123));
    assert.deepEqual(h.visible(), [], 'TRIGGERED never marks a moving-average reversal');
    market(6900, { time: 6300, value: signal.ema9, previousValue: 988,
      reversal: side === 'BUY' ? 'UP' : 'DOWN', availableAtTimeMsc: 6600000 });
    assert.equal(h.visible()[0].time, 6300, 'arrow belongs to the CLOSED reversal candle');
    assert.equal(h.visible()[0].shape, side === 'BUY' ? 'arrowUp' : 'arrowDown');
    assert.ok(h.hover(6300).includes('MME9: inversão'));
    assert.ok(h.hover(6900).includes(`Breakout LAST: ${side === 'BUY' ? 121 : 79}`));
    // Transport fixture for a second signal's cancellation; no frontend lifecycle inference.
    market(7200);
    h.send({ ...event('CANCELLED', 7200001), eventId: `${side}-other-CANCELLED` });
    assert.equal(h.visible().length, 1, 'CANCELLED adds no visual marker');
    assert.equal(h.visible()[0].text, '');
    assert.equal(h.visible()[0].position, side === 'BUY' ? 'belowBar' : 'aboveBar');
    assert.ok(h.hover(7200).includes(`SETUP91_${side}_CANCELLED`));
    const expected = h.visible();
    for (const time of [7500, 7800, 8100]) { market(time); assert.deepEqual(h.visible(), expected); }
    h.send(event('ARMED', 6600000)); // Deduplicate without moving the source marker.
    assert.deepEqual(h.visible(), expected);
    h.unmount(); h.mount(); assert.deepEqual(h.visible(), expected);
    assert.equal(h.ema()[0].value, 987.125, 'backend EMA survives remount');
    const loading = h.manager.load('2026-09-25', { force: true });
    assert.deepEqual(h.visible(), []);
    assert.deepEqual(h.ema(), []);
    assert.equal(h.hover(6300), '');
    await loading;
    h.unmount(); h.mount();
    market(6300); market(6600, { time: 6300, value: signal.ema9, previousValue: 988,
      reversal: side === 'BUY' ? 'UP' : 'DOWN', availableAtTimeMsc: 6600000 });
    assert.equal(h.visible().length, 1, 'new replay may reuse event IDs');
    h.manager.close(); assert.deepEqual(h.visible(), []); h.unmount();
  });
}

test('frontend candles never infer Setup91 signals or EMA values', async () => {
  const h = harness();
  await h.manager.load('2026-09-24'); h.mount();
  for (const [i, close] of [110, 108, 106, 104, 102, 100, 120, 130].entries()) {
    const time = 6000 + i * 300;
    h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
      candle: { time, open: close, high: close, low: close, close } });
  }
  assert.deepEqual(h.visible(), []);
  assert.deepEqual(h.ema(), []);
  h.manager.close(); h.unmount();
});


test('Replay draws only candles and the green backend Setup91 EMA9, without legacy badges', async () => {
  const h = harness();
  await h.manager.load('2026-09-24'); h.mount();
  const market = (time, closedEma9) => h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
    candle: { time, open: 100, high: 120, low: 80, close: 110 },
    sma9: 111, sma21: 112, vwap: 113, vwapSession: '2026-09-24', closedEma9 });
  market(6000); market(6000);
  assert.deepEqual(h.ema(), [], 'no EMA is calculated from developing candles');
  market(6300, { time: 6000, value: 987.123456789 });
  market(6300, { time: 6000, value: 987.123456789 });
  assert.deepEqual(h.ema(), [{ time: 6000, value: 987.123456789 }]);
  const created = h.createdSeries();
  assert.equal(created.length, 2, 'only candlesticks and one indicator series');
  assert.deepEqual(created.filter(s => s.title).map(s => ({ title: s.title, color: s.color })),
    [{ title: 'EMA9 (closed)', color: '#2e7d32' }]);
  const rendered = JSON.stringify(h.rendered());
  for (const label of ['MM9:', 'MM21:', 'VWAP:', 'HLC3']) assert.ok(!rendered.includes(label), label);
  assert.ok(rendered.includes('MME9:'));
  const replayPage = readFileSync(new URL('../src/features/replay/pages/ReplayPage.tsx', import.meta.url), 'utf8');
  assert.match(replayPage, /<Mt5CandlestickChart[^>]*setup91Only/);
  h.manager.close(); assert.deepEqual(h.ema(), []); h.unmount();
});

test('shared live chart retains its normal MM9, MM21 and VWAP visualization', async () => {
  const h = harness();
  await h.manager.load('2026-09-24'); h.mount(false);
  h.send({ type: 'MARKET_STATE', timeMsc: 6000000,
    candle: { time: 6000, open: 100, high: 120, low: 80, close: 110 },
    sma9: 111, sma21: 112, vwap: 113, vwapSession: '2026-09-24' });
  const titles = h.createdSeries().map(s => s.title);
  for (const title of ['MM9', 'MM21', 'VWAP']) assert.ok(titles.includes(title));
  h.manager.close(); h.unmount();
});


test('trade management preserves details without labels, circles or lines', async () => {
  const h = harness();
  await h.manager.load('2026-09-24'); h.mount();
  const base = { type: 'REPLAY_TRADE_EVENT', tradeId: 'trade-1', symbol: 'WIN$N', side: 'BUY',
    sequence: 10, price: 1200, entry: 1200, initialStop: 1000, risk: 200, stop: 1000,
    points: null, exit: null };
  for (const [i, stage] of ['ENTRY', 'PARTIAL', 'BREAKEVEN', 'EXIT'].entries()) {
    const time = 6000 + i * 300;
    h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
      candle: { time, open: 1200, high: 1500, low: 1000, close: 1250 } });
    h.send({ ...base, eventId: stage, stage, timeMsc: time * 1000 + 123,
      points: stage === 'EXIT' ? 125 : stage === 'PARTIAL' ? 100 : null,
      exit: stage === 'EXIT' ? 'ATR_TRAILING_STOP' : null });
  }
  assert.deepEqual(h.visible(), []);
  assert.equal(h.primitive(), undefined);
  assert.deepEqual(h.paint(), { texts: [], paths: [], rectangles: [] });
  assert.ok(h.hover(6900).includes('ATR_TRAILING_STOP'));
  assert.ok(h.hover(6900).includes('R: 200'));
  const expected = h.visible();
  h.unmount(); h.mount(); assert.deepEqual(h.visible(), expected);
  await h.manager.load('2026-09-25', { force: true });
  assert.deepEqual(h.visible(), []);
  h.manager.close(); h.unmount();
});

test('CSV trade exits add no drawings; trigger arrow survives remount and zoom', async () => {
  const csv = readFileSync(new URL('../../research-results/setup91-1r-2r/triggers.csv', import.meta.url), 'utf8').trim().split(/\r?\n/);
  const fields = csv[0].split(',');
  const row = Object.fromEntries(csv[2].split(',').map((value, i) => [fields[i], value]));
  assert.equal(row.exit, 'TARGET_2R');
  assert.equal(Number(row.exit_price), 183425);
  const h = harness(); await h.manager.load('2026-09-01'); h.mount();
  const base = { type: 'REPLAY_TRADE_EVENT', tradeId: row.id, symbol: 'WIN$N', side: 'BUY',
    sequence: 1, entry: Number(row.entry), initialStop: Number(row.initial_stop), risk: Number(row.risk), stop: Number(row.entry), points: null, exit: null };
  const send = (stage, timestamp, price, extra = {}) => {
    const timeMsc = Date.parse(timestamp), time = Math.floor(timeMsc / 300000) * 300;
    h.send({ type: 'MARKET_STATE', timeMsc, candle: { time, open: price, high: price + 20, low: price - 20, close: price } });
    h.send({ ...base, stage, eventId: stage, timeMsc, price, ...extra });
    return time;
  };
  const entryTime = send('ENTRY', row.trigger_time_utc, Number(row.entry));
  const exitTime = send('EXIT', row.exit_time_utc, Number(row.exit_price), { points: Number(row.points), exit: row.exit });
  assert.equal(new Date(exitTime * 1000).toISOString(), '2026-09-01T11:20:00.000Z');
  const before = h.paint();
  assert.deepEqual(before, { texts: [], paths: [], rectangles: [] });
  send('ENTRY', '2026-09-01T12:06:26.331Z', 183065, { eventId: 'sale', tradeId: 'sale', side: 'SELL' });
  h.send({ type: 'REPLAY_SETUP_EVENT', eventId: 'sale-trigger', setupType: 'SETUP91_SELL_TRIGGERED',
    timeMsc: Date.parse('2026-09-01T12:06:26.331Z'), candleTimeMsc: Date.parse('2026-09-01T12:00:00Z') });
  const verify = () => {
    const drawn = h.paint();
    assert.deepEqual(drawn, { texts: [], paths: [], rectangles: [] });
    assert.equal(h.rawMarkers().length, 0, 'trade triggers do not mark EMA reversals');
    assert.equal(h.primitive(), undefined);
    return drawn;
  };
  verify();
  h.zoom(0.2); verify();
  h.unmount(); h.mount(); verify();
  await h.manager.load('2026-09-02', { force: true });
  assert.equal(h.paint().texts.length, 0); assert.equal(h.paint().paths.length, 0);
  h.manager.close(); h.unmount();
});
test('rejected purchase has no visual label or marker, including after remount', async () => {
  const h = harness(); await h.manager.load('2026-09-01'); h.mount();
  const timeMsc = Date.parse('2026-09-01T10:35:05.187Z');
  const time = Math.floor(timeMsc / 300000) * 300;
  h.send({ type: 'MARKET_STATE', timeMsc, candle: { time, open: 181285, high: 181300, low: 181200, close: 181285 } });
  h.send({ type: 'REPLAY_TRADE_EVENT', stage: 'REJECTED', eventId: 'buy-rejected', tradeId: 'buy',
    symbol: 'WINV26', side: 'BUY', timeMsc, sequence: 623399, price: 181285, entry: 181285,
    initialStop: 180215, risk: 1070, stop: 180215, points: null, exit: null,
    reason: 'R 1070 fora de 300 a 650 pts' });
  const verify = () => {
    const painted = h.paint();
    assert.equal(painted.texts.length, 0);
    assert.equal(h.rawMarkers().length, 0);
    assert.ok(!painted.texts.some(t => t.text.includes('GAIN')));
    assert.equal(painted.paths.filter(p => p.dash.length).length, 0);
  };
  verify(); h.unmount(); h.mount(); verify(); h.manager.close(); h.unmount();
});

for (const direction of ['UP', 'DOWN']) {
  test(`EMA9 ${direction} marks only the closed reversal candle, deduplicates and persists`, async () => {
    const h = harness(); await h.manager.load('2026-09-09'); h.mount();
    const send = (time, closedEma9) => h.send({ type: 'MARKET_STATE', timeMsc: time * 1000,
      candle: { time, open: 100, high: 120, low: 80, close: 110 }, closedEma9 });
    send(6000); send(6300);
    const ema = { time: 6300, value: 101, previousValue: 100, reversal: direction, availableAtTimeMsc: 6600020 };
    send(6300, ema); // Still developing: cannot place an arrow.
    assert.deepEqual(h.rawMarkers(), []);
    send(6600, { ...ema, availableAtTimeMsc: 6500000 }); // Premature closure rejected.
    assert.deepEqual(h.rawMarkers(), []);
    send(6600, ema); send(6900, ema);
    assert.equal(h.rawMarkers().length, 1);
    assert.equal(h.rawMarkers()[0].time, 6300);
    assert.equal(h.rawMarkers()[0].shape, direction === 'UP' ? 'arrowUp' : 'arrowDown');
    assert.equal(h.rawMarkers()[0].position, direction === 'UP' ? 'belowBar' : 'aboveBar');
    assert.equal(h.rawMarkers()[0].text, '');
    assert.ok(h.hover(6300).includes('MME9 anterior: 100 | Atual: 101'));
    const expected = h.rawMarkers(); h.unmount(); h.mount();
    assert.deepEqual(h.rawMarkers(), expected);
    await h.manager.load('2026-09-10', { force: true });
    assert.deepEqual(h.rawMarkers(), []); h.manager.close(); h.unmount();
  });
}
