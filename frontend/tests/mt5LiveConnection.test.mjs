import { test } from 'node:test';
import assert from 'node:assert/strict';
import { connectMt5Ticks, mt5SocketUrl } from '../src/features/win/api/mt5LiveConnection.ts';

class FakeSocket {
  closed = false;
  close() { this.closed = true; this.onclose?.(); }
  message(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
}
const tick = { type: 'tick', symbol: 'WINV26', time: 1789994563, timeMsc: 1789994563184,
  last: 187585, bid: 187580, ask: 187585, volume: 4 };

test('derives ws/wss URL from backend configuration', () => {
  assert.equal(mt5SocketUrl('http://localhost:8080', 'http://localhost:5173'), 'ws://localhost:8080/ws/mt5/ticks');
  assert.equal(mt5SocketUrl('https://example.com/backend/', 'https://example.com'), 'wss://example.com/backend/ws/mt5/ticks');
});
test('updates quote from ticks without modifying raw timestamps and reports feed failure', () => {
  const states = [];
  const socket = new FakeSocket();
  const cleanup = connectMt5Ticks('ws://test', state => states.push(state), () => socket);
  socket.onopen();
  socket.message(tick);
  assert.deepEqual(states.at(-1), { status: 'live', tick });
  socket.message({ ...tick, timeMsc: tick.timeMsc + 20, bid: 187585 });
  assert.equal(states.at(-1).tick.timeMsc, tick.timeMsc + 20);
  socket.message({ type: 'status', available: false });
  assert.deepEqual(states.at(-1), { status: 'unavailable', tick: null });
  cleanup();
  assert.equal(socket.onmessage, null);
  assert.equal(socket.onclose, null);
  assert.equal(socket.closed, true);
});
test('reconnects after 3s and cancels pending reconnect on cleanup', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const sockets = [];
  const states = [];
  const cleanup = connectMt5Ticks('ws://test', state => states.push(state), () => {
    const socket = new FakeSocket(); sockets.push(socket); return socket;
  });
  sockets[0].close();
  assert.equal(states.at(-1).status, 'disconnected');
  t.mock.timers.tick(2999);
  assert.equal(sockets.length, 1);
  t.mock.timers.tick(1);
  assert.equal(sockets.length, 2);
  sockets[1].close();
  cleanup();
  t.mock.timers.tick(10000);
  assert.equal(sockets.length, 2);
});
test('invalid JSON closes connection and exposes disconnected state', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const socket = new FakeSocket();
  const states = [];
  const cleanup = connectMt5Ticks('ws://test', state => states.push(state), () => socket);
  socket.onmessage({ data: 'invalid JSON' });
  assert.equal(socket.closed, true);
  assert.equal(states.at(-1).status, 'disconnected');
  cleanup();
});


test('preserves backend SMA precision and candle timestamp in live payload', () => {
  const states = []; const socket = new FakeSocket();
  const cleanup = connectMt5Ticks('ws://test', state => states.push(state), () => socket);
  const enriched = { ...tick, sma9: 188375.5555555556, sma9Time: 1789994400, sma21: 188321.12345678, sma21Time: 1789994400 };
  socket.message(enriched);
  assert.deepEqual(states.at(-1).tick, enriched);
  socket.message({ ...tick, sma9: null, sma9Time: null });
  assert.equal(states.at(-1).status, 'live');
  assert.equal(states.at(-1).tick.sma9, null);
  cleanup();
});
