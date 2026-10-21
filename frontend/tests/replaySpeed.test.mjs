import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplaySessionManager } from '../src/features/replay/api/replaySessionManager.ts';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { createReplayTradeStore } from '../src/features/replay/api/replayTradeStore.ts';
import { createReplayOccurrenceStore } from '../src/features/replay/api/replayOccurrenceStore.ts';
import { createReplaySetupStore, isReplaySetupType } from '../src/features/replay/api/replaySetupStore.ts';

test('socket queues speed before PLAY, applies live changes and rejects invalid speeds', () => {
  const source = readFileSync(new URL('../src/features/replay/api/replayConnection.ts', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  });
  const exports = {};
  const mocks = {
    './replayTradeStore': { createReplayTradeStore },
    './replayOccurrenceStore': { createReplayOccurrenceStore },
    './replaySetupStore': { createReplaySetupStore, isReplaySetupType },
    '@/features/win/models/mt5Intrabar': { M5_SECONDS: 300 },
  };
  runInNewContext(outputText, { exports, require: name => mocks[name], URL, WebSocket: { OPEN: 1, CONNECTING: 0 } });
  const sent = [];
  const socket = { readyState: 0, send: command => sent.push(command), close() {} };
  const controller = exports.connectReplay('test', 'http://localhost', () => {}, () => {}, () => socket);
  controller.setSpeed(1);
  controller.play();
  assert.deepEqual(sent, []);
  socket.readyState = 1;
  socket.onopen();
  controller.setSpeed(0.5);
  controller.setSpeed(100);
  assert.deepEqual(sent, ['SPEED 1', 'PLAY', 'SPEED 0.5', 'SPEED 100']);
  for (const invalid of [0, -1, NaN, Infinity, 101]) assert.throws(() => controller.setSpeed(invalid));
  controller.close();
  controller.setSpeed(2);
  assert.equal(sent.length, 4);
});

test('replay defaults to 1x and preserves speed across reloads and changes during playback', async () => {
  const commands = [];
  const manager = createReplaySessionManager({
    createSession: async () => ({ replayId: 'test', status: 'READY', currentTimeMsc: 0 }),
    connect: () => ({
      setSpeed: speed => commands.push(['speed', speed]),
      play: () => commands.push(['play']), pause() {}, close() {},
    }),
  });
  await manager.load('2026-09-24');
  manager.play();
  manager.setSpeed(5);
  await manager.load('2026-09-25', { force: true });
  assert.deepEqual(commands, [['speed', 1], ['play'], ['speed', 5], ['speed', 5]]);
  for (const invalid of [0, -1, NaN, Infinity, 101]) assert.throws(() => manager.setSpeed(invalid));
  manager.close();
});
