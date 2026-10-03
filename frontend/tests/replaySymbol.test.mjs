import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplaySessionManager } from '../src/features/replay/api/replaySessionManager.ts';

test('reloading with another symbol closes the previous visual session and forwards the exact symbol', async () => {
  const calls = [];
  let closed = 0;
  const manager = createReplaySessionManager({
    createSession: async (symbol, date) => {
      calls.push([symbol, date]);
      return { replayId: String(calls.length), status: 'READY', currentTimeMsc: 0 };
    },
    connect: () => ({ close() { closed++; }, play() {}, pause() {} }),
  });
  await manager.load('2026-08-20', { symbol: 'WIN$N', force: true });
  await manager.load('2026-09-23', { symbol: 'WINV26', force: true });
  assert.deepEqual(calls, [['WIN$N', '2026-08-20'], ['WINV26', '2026-09-23']]);
  assert.equal(closed, 1);
  manager.close();
});
