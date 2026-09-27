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

test('retains only the three M5 setup markers at their source candle', () => {
  const store = createReplaySetupStore(300);
  const markers = [];
  store.add(setupEvent('crz-0', 'CRZ09_UP', 6_300_000, 6_000_000));
  store.add(setupEvent('rj-0', 'RJ09_UP', 6_600_000, 6_300_000));
  store.add(setupEvent('pullb-0', 'PULLB09_UP', 6_600_015, 6_600_000));
  store.add(setupEvent('other-0', 'OTHER', 6_600_016, 6_600_000));
  store.attach(marker => markers.push(marker));

  assert.deepEqual(markers, [
    { eventId: 'crz-0', time: 6000, setupType: 'CRZ09_UP' },
    { eventId: 'rj-0', time: 6300, setupType: 'RJ09_UP' },
    { eventId: 'pullb-0', time: 6600, setupType: 'PULLB09_UP' },
  ]);
});

test('deduplicates setup events and replays them after chart remount', () => {
  const store = createReplaySetupStore(300);
  const firstChart = [];
  const detach = store.attach(marker => firstChart.push(marker));
  const event = setupEvent('pullb-0', 'PULLB09_UP', 6_600_015, 6_600_000);
  store.add(event);
  store.add(event);
  detach();

  const reattachedChart = [];
  store.attach(marker => reattachedChart.push(marker));
  assert.equal(firstChart.length, 1);
  assert.deepEqual(reattachedChart, [
    { eventId: 'pullb-0', time: 6600, setupType: 'PULLB09_UP' },
  ]);
});
