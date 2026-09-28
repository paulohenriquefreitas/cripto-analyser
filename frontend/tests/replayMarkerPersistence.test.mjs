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
  const series = { setData() {}, update() {} };
  const chart = {
    addSeries: () => series, removeSeries() {}, remove() {},
    timeScale: () => ({ fitContent() {} }),
  };
  const mocks = {
    '@mui/material': {
      useTheme: () => ({ palette: { background: {}, text: {}, success: {}, error: {}, warning: {} } }),
    },
    react: {
      useRef: () => ({ current: {} }),
      useState: () => [false, failed => assert.equal(failed, false, 'chart initialization failed')],
      useEffect: effect => { cleanup = effect(); },
    },
    'react/jsx-runtime': { jsx() {}, jsxs() {} },
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
    mount: () => Mt5CandlestickChart({ controller: manager.getSnapshot().controller }),
    unmount: () => cleanup(),
    visible: () => visible,
    send: message => sockets.at(-1).onmessage({ data: JSON.stringify(message) }),
  };
}

test('setup markers survive three later M5 buckets, additions, duplicates and remount; load/close clear them', async () => {
  const h = harness();
  await h.manager.load('2026-09-24');
  h.mount();
  const market = time => h.send({
    type: 'MARKET_STATE', timeMsc: time * 1000, status: 'PLAYING',
    candle: { time, open: 100, high: 112, low: 99, close: 112 },
  });
  const event = (eventId, setupType, time, available = time) => ({
    type: 'REPLAY_SETUP_EVENT', eventId, setupType,
    timeMsc: available * 1000 + 2, candleTimeMsc: time * 1000,
  });
  market(6000);
  market(6300);
  h.send(event('crz-0', 'CRZ09_UP', 6000, 6300));
  market(6600);
  h.send(event('rj-0', 'RJ09_UP', 6300, 6600));
  const pullback = event('pullb-0', 'PULLB09_UP', 6600);
  h.send(pullback);
  const expected = [
    { time: 6000, text: 'CRZ09 ↑' },
    { time: 6300, text: 'RJ09 ↑' },
    { time: 6600, text: 'PULLB09 ↑' },
  ];
  const check = () => assert.deepEqual(h.visible().map(({ time, text }) => ({ time, text })), expected);
  check();
  for (const time of [6900, 7200, 7500]) {
    market(time);
    check();
    h.send(pullback);
    market(time);
    check();
  }
  const next = event('crz-1', 'CRZ09_UP', 7500, 7800);
  market(7800);
  h.send(next);
  h.send(next);
  expected.push({ time: 7500, text: 'CRZ09 ↑' });
  check();
  h.send({ type: 'CLOCK', timeMsc: 7800000, status: 'PAUSED' });
  h.send({ type: 'COMPLETED', timeMsc: 7800000, status: 'COMPLETED' });
  check();
  h.unmount();
  h.mount();
  check(); // Store still contains every event exactly once.
  const loading = h.manager.load('2026-09-25', { force: true });
  assert.deepEqual(h.visible(), []);
  await loading;
  h.unmount();
  h.mount();
  assert.deepEqual(h.visible(), []);
  market(6600);
  h.send(pullback); // Same ID is valid in the new session.
  assert.equal(h.visible().length, 1);
  h.manager.close();
  assert.deepEqual(h.visible(), []);
  h.unmount();
});
