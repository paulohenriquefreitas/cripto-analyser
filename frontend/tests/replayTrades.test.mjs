import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplayTradeStore, tradeLabel } from '../src/features/replay/api/replayTradeStore.ts';

test('trade markers preserve backend times/prices/results, deduplicate and survive reattachment', () => {
  const store = createReplayTradeStore();
  const message = { type: 'REPLAY_TRADE_EVENT', eventId: 'exit', tradeId: 'trade-1', stage: 'EXIT',
    side: 'SELL', symbol: 'WIN$N', timeMsc: 1790157600123, sequence: 4,
    price: 186500, entry: 187000, initialStop: 187200, risk: 200, stop: 186550,
    points: 350, exit: 'ATR_TRAILING_STOP' };
  store.add(message); store.add(message);
  const seen = [];
  const detach = store.attach(marker => seen.push(marker));
  assert.equal(seen.length, 1);
  assert.equal(seen[0].timeMsc, message.timeMsc);
  assert.equal(seen[0].points, 350);
  assert.equal(seen[0].price, 186500);
  detach();
  const remounted = [];
  store.attach(marker => remounted.push(marker));
  assert.equal(remounted.length, 1);
  assert.equal(tradeLabel(message), 'GAIN +350 pts');
  assert.equal(tradeLabel({ ...message, points: 0 }), '0x0 0 pts');
  assert.equal(tradeLabel({ ...message, points: -205 }), 'STOP -205 pts');
  assert.equal(tradeLabel({ ...message, stage: 'BREAKEVEN' }), 'BE protegido');
  store.clear();
  const empty = []; store.attach(marker => empty.push(marker));
  assert.equal(empty.length, 0);
});
