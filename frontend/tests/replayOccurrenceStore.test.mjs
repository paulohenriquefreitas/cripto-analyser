import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplayOccurrenceStore } from '../src/features/replay/api/replayOccurrenceStore.ts';

const occurrence = (occurrenceId, timeMsc, direction) => ({
  type: 'RULE_OCCURRENCE', occurrenceId, timeMsc, direction,
});

test('retains each backend occurrence through coalescing and repeated delivery', () => {
  const store = createReplayOccurrenceStore(300);
  const markers = [];
  store.add(occurrence('rule-0', 6_000_004, 'LONG'));
  store.add(occurrence('rule-0', 6_000_004, 'LONG'));
  store.add(occurrence('rule-1', 6_000_004, 'SHORT'));
  store.attach(marker => markers.push(marker));

  assert.deepEqual(markers, [
    { occurrenceId: 'rule-0', time: 6000, direction: 'LONG' },
    { occurrenceId: 'rule-1', time: 6000, direction: 'SHORT' },
  ]);
});

test('publishes new markers once and reattaches existing markers after pause or chart remount', () => {
  const store = createReplayOccurrenceStore(300);
  const firstChart = [];
  const detach = store.attach(marker => firstChart.push(marker));
  const next = occurrence('rule-0', 6_300_001, 'LONG');
  store.add(next);
  store.add(next);
  detach();

  const reattachedChart = [];
  store.attach(marker => reattachedChart.push(marker));
  assert.equal(firstChart.length, 1);
  assert.deepEqual(reattachedChart, [
    { occurrenceId: 'rule-0', time: 6300, direction: 'LONG' },
  ]);
});
