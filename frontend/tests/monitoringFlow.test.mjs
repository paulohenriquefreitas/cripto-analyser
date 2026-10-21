import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSwingLevelMonitoring } from '../src/features/win/models/swingLevelMonitoring.ts';
import { createMt5Intrabar } from '../src/features/win/models/mt5Intrabar.ts';
import { flowLines, MonitoringFlowPrimitive } from '../src/features/win/components/MonitoringFlowPrimitive.ts';
import { connectMt5Ticks } from '../src/features/win/api/mt5LiveConnection.ts';

const flow = (timeMsc = 31000, quality = 'GOOD') => ({
  type: 'FLOW', symbol: 'WINV26', timeMsc, lastTimeMsc: timeMsc, last: 1000, volumeSource: 'MT5_VOLUME_REAL',
  windows: [5, 10, 30].map(seconds => ({ seconds, fromTimeMsc: timeMsc - seconds * 1000, toTimeMsc: timeMsc,
    events: 4, buyVolume: 20, sellVolume: 5, unknownVolume: 5, delta: 15,
    buyPercent: 200 / 3, sellPercent: 50 / 3, unknownPercent: 50 / 3,
    lastDisplacement: 0, quality, alert: 'POSSIBLE_ABSORPTION' })),
});
const pivots = [{ type: 'HIGH', time: 600, price: 1000, confirmedAt: 1500 },
  { type: 'LOW', time: 900, price: 850, confirmedAt: 1800 }];

test('all monitored levels share one flow and retain an independent immutable pre-activation sample', () => {
  const monitor = createSwingLevelMonitoring();
  const before = flow(); monitor.receiveFlow(before); monitor.addConfirmed(pivots);
  monitor.receiveLast(950, 31001);
  const active = monitor.getSnapshot().levels;
  assert.equal(active.length, 2);
  assert.ok(active.every(level => level.state === 'MONITORING' && level.baseline === before));
  const after = flow(32000); after.windows[0].buyVolume = 40;
  monitor.receiveFlow(after);
  assert.equal(monitor.getSnapshot().flow, after);
  assert.ok(monitor.getSnapshot().levels.every(level => level.baseline === before));
  assert.equal(before.windows[0].buyVolume, 20);
  const lines = flowLines(monitor.getSnapshot());
  assert.equal(lines.filter(line => line.startsWith('5s |')).length, 1);
  assert.equal(lines.filter(line => line.startsWith('Pré-aproximação')).length, 2);
  monitor.receiveLast(1050, 32001); // Resistance break retains the TOP's sample and flow.
  assert.equal(monitor.getSnapshot().levels[0].baseline, before);
  monitor.receiveLast(1151, 33000);
  assert.deepEqual(flowLines(monitor.getSnapshot()), []);
  monitor.receiveLast(950, 34000);
  assert.ok(monitor.getSnapshot().levels.every(level => level.baseline === after));
  monitor.reset();
  assert.equal(monitor.getSnapshot().flow, null);
});

test('future flow never becomes a pre-activation baseline and low quality suppresses alerts', () => {
  const monitor = createSwingLevelMonitoring();
  const future = flow(32000, 'HIGH_UNKNOWN'); future.lastTimeMsc = 31000;
  monitor.receiveFlow(future);
  monitor.addConfirmed(pivots); monitor.receiveLast(950, 31000);
  assert.ok(monitor.getSnapshot().levels.every(level => !level.baseline));
  assert.ok(flowLines(monitor.getSnapshot()).every(line => !line.includes('POSSIBLE_ABSORPTION')));
  monitor.receiveFlow(null);
  assert.ok(flowLines(monitor.getSnapshot()).some(line => line.includes('indisponível')));
});

test('T&T LAST updates proximity before candles, while old price samples cannot roll it back', () => {
  const monitor = createSwingLevelMonitoring();
  const before = flow(31000); before.last = 800;
  monitor.receiveFlow(before); monitor.addConfirmed([pivots[0]]);
  assert.equal(monitor.getSnapshot().levels[0].state, 'WAITING');
  const crossing = flow(32000); crossing.last = 1050;
  monitor.receiveFlow(crossing);
  assert.equal(monitor.getSnapshot().last, 1050);
  assert.equal(monitor.getSnapshot().levels[0].state, 'MONITORING');
  assert.equal(monitor.getSnapshot().levels[0].baseline, before);
  monitor.receiveLast(800, 31999);
  assert.equal(monitor.getSnapshot().last, 1050);
  const leaving = flow(33000); leaving.last = 1151;
  monitor.receiveFlow(leaving);
  assert.equal(monitor.getSnapshot().levels[0].state, 'WAITING');
  assert.deepEqual(flowLines(monitor.getSnapshot()), []);
});

test('flow received before REST or chart mounting reaches the chart without duplicating global metrics', async () => {
  const controller = createMt5Intrabar(async () => []);
  controller.start();
  const before = flow(); controller.receiveFlow(before);
  const seen = [];
  const sink = { setHistory() {}, updateLast() {}, onFlowSnapshot: value => seen.push(value) };
  const detach = controller.attachChart(sink);
  assert.equal(seen[0], before);
  controller.receiveFlow(flow(30000)); assert.equal(seen.length, 1);
  controller.receiveFlow(null); assert.equal(seen.at(-1), null);
  detach(); controller.attachChart(sink); assert.equal(seen.at(-1), null);
  controller.stop();
  await Promise.resolve();
});

test('FLOW is routed separately from quotes and reconnect invalidates previously healthy metrics', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const sockets = [], seen = [], states = [];
  const cleanup = connectMt5Ticks('ws://test', value => states.push(value), () => {
    const socket = { close() { this.onclose?.(); } }; sockets.push(socket); return socket;
  }, value => seen.push(value));
  sockets[0].onmessage({ data: JSON.stringify(flow()) });
  assert.equal(seen.at(-1).windows[0].delta, 15);
  assert.equal(states.at(-1).status, 'connecting');
  sockets[0].close(); assert.equal(seen.at(-1), null);
  t.mock.timers.tick(3000);
  sockets[1].onmessage({ data: JSON.stringify(flow(32000)) });
  assert.equal(seen.at(-1).timeMsc, 32000);
  cleanup();
});

test('metrics panel follows candle coordinates and disappears after deactivation', () => {
  const monitor = createSwingLevelMonitoring(); monitor.receiveFlow(flow());
  monitor.addConfirmed(pivots); monitor.receiveLast(950, 31001);
  const primitive = new MonitoringFlowPrimitive('#000', '#fff');
  let x = 400; const drawn = [];
  primitive.attached({ chart: { timeScale: () => ({ timeToCoordinate: () => x }) },
    series: { priceToCoordinate: () => 400 }, requestUpdate() {} });
  primitive.setSnapshot(1000, 1000, monitor.getSnapshot());
  const target = { useMediaCoordinateSpace: callback => callback({ mediaSize: { width: 800, height: 480 },
    context: { save() {}, restore() {}, fillRect() {}, measureText: text => ({ width: text.length * 5 }),
      fillText: (text, x, y) => drawn.push({ text, x, y }) } }) };
  primitive.paneViews()[0].renderer().draw(target);
  assert.ok(drawn.some(line => line.text.includes('UNKNOWN')));
  assert.ok(drawn.every(line => line.y < 400));
  drawn.length = 0; x = -1;
  primitive.paneViews()[0].renderer().draw(target); assert.equal(drawn.length, 0);
  x = 400; monitor.receiveLast(1151, 32000);
  primitive.setSnapshot(1000, 1151, monitor.getSnapshot());
  primitive.paneViews()[0].renderer().draw(target); assert.equal(drawn.length, 0);
});
