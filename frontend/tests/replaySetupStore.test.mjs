import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplaySetupStore } from '../src/features/replay/api/replaySetupStore.ts';

const setupEvent = (eventId, setupType, timeMsc, candleTimeMsc) => ({
  type: 'REPLAY_SETUP_EVENT',
  eventId,
  setupType,
  timeMsc,
  candleTimeMsc,
});

test('rejects every Pullback09 event and unknown event types', () => {
  const store = createReplaySetupStore(300);
  const markers = [];
  for (const type of ['CRZ09_UP', 'RJ09_UP', 'PULLB09_UP', 'CRZ09_DOWN', 'RJ09_DOWN', 'PULLB09_DOWN', 'OTHER']) {
    store.add(setupEvent(type, type, 6_600_015, 6_300_000));
  }
  store.attach(marker => markers.push(marker));
  assert.deepEqual(markers, []);
});

test('retains all six Setup91 types at signal/event buckets and deduplicates across remount', () => {
  const store = createReplaySetupStore(300);
  const markers = [];
  const detach = store.attach(marker => markers.push(marker));
  const types = ['SETUP91_BUY_ARMED', 'SETUP91_BUY_TRIGGERED', 'SETUP91_BUY_CANCELLED',
    'SETUP91_SELL_ARMED', 'SETUP91_SELL_TRIGGERED', 'SETUP91_SELL_CANCELLED'];
  for (const type of types) {
    const event = setupEvent(type, type, 6_600_015, 6_300_000);
    store.add(event); store.add(event);
  }
  assert.equal(markers.length, 6);
  assert.deepEqual(markers.map(m => m.setupType), types);
  assert.deepEqual(markers.map(m => m.time), [6300, 6600, 6600, 6300, 6600, 6600]);
  detach();
  const reattached = [];
  store.attach(marker => reattached.push(marker));
  assert.deepEqual(reattached, markers);
  store.clear();
  const afterReset = [];
  store.attach(marker => afterReset.push(marker));
  assert.deepEqual(afterReset, []);
});
