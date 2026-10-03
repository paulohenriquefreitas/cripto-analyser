import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createReplaySessionManager } from '../src/features/replay/api/replaySessionManager.ts';
import { createReplayOccurrenceStore } from '../src/features/replay/api/replayOccurrenceStore.ts';
import { createReplaySetupStore } from '../src/features/replay/api/replaySetupStore.ts';

function createMockController(id, onClock, onError) {
  let closed = false;
  let sink = undefined;
  const occurrences = createReplayOccurrenceStore(300);
  const setupEvents = createReplaySetupStore(300);

  return {
    id,
    attachChart(next) {
      if (closed) return () => {};
      sink = next;
      const detachOcc = occurrences.attach(marker => next.addRuleOccurrence?.(marker));
      const detachSetup = setupEvents.attach(marker => next.addReplaySetupMarker?.(marker));
      return () => {
        detachOcc();
        detachSetup();
        if (sink === next) sink = undefined;
      };
    },
    play() {},
    pause() {},
    close() {
      if (closed) return;
      closed = true;
      occurrences.clear();
      setupEvents.clear();
      if (sink) {
        sink.setHistory([]);
        sink = undefined;
      }
    },
    isClosed() {
      return closed;
    },
    // Simulation hooks
    simulateClock(timeMsc, status) {
      if (closed) return;
      onClock(timeMsc, status);
    },
    simulateError(msg) {
      if (closed) return;
      onError(msg);
    },
    simulateCandle(candle) {
      if (closed) return;
      sink?.updateLast(candle);
    },
    simulateSetupEvent(event) {
      if (closed) return;
      setupEvents.add(event);
    },
    simulateRuleOccurrence(occurrence) {
      if (closed) return;
      occurrences.add(occurrence);
    },
  };
}

test('A. nova carga limpa imediatamente os dados anteriores', async () => {
  let sessionSeq = 0;
  let activeMock = null;
  const manager = createReplaySessionManager({
    symbol: 'WINV26',
    apiUrl: 'http://localhost:8080',
    createSession: async () => ({
      replayId: `session-${++sessionSeq}`,
      status: 'READY',
      currentTimeMsc: 6_000_000,
    }),
    connect: (id, url, onClock, onError) => {
      activeMock = createMockController(id, onClock, onError);
      return activeMock;
    },
  });

  // 1ª carga é realizada com sucesso
  await manager.load('2026-09-24');
  assert.equal(manager.getSnapshot().status, 'READY');
  assert.equal(manager.getSnapshot().clock, 6_000_000);
  assert.ok(manager.getSnapshot().controller);

  let sinkHistory = [
    { time: 6000, open: 100, high: 105, low: 95, close: 102, sma9: 101, sma21: 100, vwap: 100.5 },
  ];
  let setupMarkers = [];
  const controller1 = manager.getSnapshot().controller;
  controller1.attachChart({
    setHistory: candles => { sinkHistory = candles; setupMarkers = []; },
    updateLast: () => {},
    addReplaySetupMarker: marker => { setupMarkers.push(marker); },
  });

  // Adiciona marcador de setup para simular Setup91
  activeMock.simulateSetupEvent({
    type: 'REPLAY_SETUP_EVENT',
    eventId: 'evt-1',
    timeMsc: 6_000_000,
    candleTimeMsc: 6_000_000,
    setupType: 'SETUP91_BUY_ARMED',
  });
  assert.equal(setupMarkers.length, 1);

  // Prepara a 2ª carga com Promise pendente para verificar estado IMEDIATO durante LOADING
  let resolveLoad2;
  const load2Promise = new Promise(resolve => { resolveLoad2 = resolve; });
  manager.setCreateSession(async () => load2Promise);

  // Dispara nova carga (Carga 2)
  const loading = manager.load('2026-09-25', { force: true });

  // Requisito 1: Ao clicar CARREGAR, limpar IMEDIATAMENTE a sessão visual anterior:
  // - candles, MM9, MM21, VWAP, EMA9, Setup91;
  // - relógio/progresso do Replay;
  // - erros anteriores;
  // - stores relacionados ao Replay;
  // - Replay/WebSocket anterior encerrado.
  const snapImmediate = manager.getSnapshot();
  assert.equal(snapImmediate.status, 'LOADING');
  assert.equal(snapImmediate.clock, undefined);
  assert.equal(snapImmediate.error, null);
  assert.equal(snapImmediate.controller, undefined);

  // O chart sink recebeu setHistory([]) e os marcadores de setup foram zerados
  assert.deepEqual(sinkHistory, []);
  assert.deepEqual(setupMarkers, []);
  assert.equal(controller1.isClosed(), true);

  // Conclui a carga 2
  resolveLoad2({ replayId: 'session-2', status: 'READY', currentTimeMsc: 6_300_000 });
  await loading;
  assert.equal(manager.getSnapshot().status, 'READY');
});

test('B. clique duplo não cria duas sessões', async () => {
  let sessionCalls = 0;
  let resolveSession;
  const pendingSession = new Promise(resolve => { resolveSession = resolve; });

  const manager = createReplaySessionManager({
    createSession: async () => {
      sessionCalls++;
      return pendingSession;
    },
    connect: (id, url, onClock, onError) => createMockController(id, onClock, onError),
  });

  // Dois cliques em rápida sucessão (clique duplo)
  const call1 = manager.load('2026-09-24');
  const call2 = manager.load('2026-09-24');

  // Apenas UMA chamada ao backend deve ocorrer
  assert.equal(sessionCalls, 1);

  resolveSession({ replayId: 'session-1', status: 'READY', currentTimeMsc: 6_000_000 });
  await Promise.all([call1, call2]);

  assert.equal(sessionCalls, 1);
  assert.equal(manager.getSnapshot().status, 'READY');
});

test('C. resposta antiga chegando depois da nova é ignorada', async () => {
  let resolveA;
  let resolveB;
  const promiseA = new Promise(resolve => { resolveA = resolve; });
  const promiseB = new Promise(resolve => { resolveB = resolve; });

  const connectedSessionIds = [];
  const manager = createReplaySessionManager({
    createSession: async (symbol, date) => {
      return date === '2026-09-24' ? promiseA : promiseB;
    },
    connect: (id, url, onClock, onError) => {
      connectedSessionIds.push(id);
      return createMockController(id, onClock, onError);
    },
  });

  // Carga A inicia (Geração 1)
  const loadA = manager.load('2026-09-24');
  assert.equal(manager.getSnapshot().loadGeneration, 1);

  // Carga B passa a ser a carga atual (Geração 2)
  const loadB = manager.load('2026-09-25', { force: true });
  assert.equal(manager.getSnapshot().loadGeneration, 2);

  // Resposta A chega DEPOIS que a carga B já é a atual
  resolveA({ replayId: 'session-A', status: 'READY', currentTimeMsc: 6_000_000 });
  await loadA;

  // A resposta A DEVE SER COMPLETAMENTE IGNORADA:
  // - Não abre WebSocket/connect para session-A
  // - Não altera o status (permanece LOADING da carga atual B)
  // - Não altera o controller (continua undefined)
  assert.equal(connectedSessionIds.includes('session-A'), false);
  assert.equal(manager.getSnapshot().status, 'LOADING');
  assert.equal(manager.getSnapshot().controller, undefined);

  // Agora resposta B chega
  resolveB({ replayId: 'session-B', status: 'READY', currentTimeMsc: 6_300_000 });
  await loadB;

  // Carga B assume normalmente a sessão
  assert.equal(connectedSessionIds.includes('session-B'), true);
  assert.equal(manager.getSnapshot().status, 'READY');
  assert.equal(manager.getSnapshot().clock, 6_300_000);
});

test('D. WebSocket/callback da sessão antiga não consegue atualizar a nova tela', async () => {
  let controllerA = null;
  let controllerB = null;

  const manager = createReplaySessionManager({
    createSession: async (symbol, date) => ({
      replayId: date === '2026-09-24' ? 'session-A' : 'session-B',
      status: 'READY',
      currentTimeMsc: 6_000_000,
    }),
    connect: (id, url, onClock, onError) => {
      const mock = createMockController(id, onClock, onError);
      if (id === 'session-A') controllerA = mock;
      if (id === 'session-B') controllerB = mock;
      return mock;
    },
  });

  // Carrega Sessão A
  await manager.load('2026-09-24');
  assert.ok(controllerA);
  assert.equal(controllerA.isClosed(), false);

  // Inicia Sessão B
  await manager.load('2026-09-25', { force: true });
  assert.ok(controllerB);
  assert.equal(controllerA.isClosed(), true);
  assert.equal(controllerB.isClosed(), false);

  // Sessão A antiga tenta disparar callbacks tardios via WebSocket/simulação
  controllerA.simulateClock(9_999_999, 'PLAYING');
  controllerA.simulateError('Erro antigo da sessão A');

  // Os dados da sessão A não podem ter alterado o snapshot atual da sessão B
  const snap = manager.getSnapshot();
  assert.equal(snap.status, 'READY');
  assert.equal(snap.clock, 6_000_000);
  assert.equal(snap.error, null);
  assert.equal(snap.controller, controllerB);
});

test('E. erro de uma carga antiga não altera a carga atual', async () => {
  let rejectA;
  let resolveB;
  const promiseA = new Promise((_, reject) => { rejectA = reject; });
  const promiseB = new Promise(resolve => { resolveB = resolve; });

  const manager = createReplaySessionManager({
    createSession: async (symbol, date) => {
      return date === '2026-09-24' ? promiseA : promiseB;
    },
    connect: (id, url, onClock, onError) => createMockController(id, onClock, onError),
  });

  const loadA = manager.load('2026-09-24');
  const loadB = manager.load('2026-09-25', { force: true });

  // Erro da carga A rejeita enquanto carga B está em andamento
  rejectA(new Error('Falha de rede da carga A'));
  await loadA;

  // Snapshot não deve ter o erro da carga A
  assert.equal(manager.getSnapshot().error, null);
  assert.equal(manager.getSnapshot().status, 'LOADING');

  // Carga B resolve com sucesso
  resolveB({ replayId: 'session-B', status: 'READY', currentTimeMsc: 6_300_000 });
  await loadB;

  assert.equal(manager.getSnapshot().status, 'READY');
  assert.equal(manager.getSnapshot().error, null);
  assert.ok(manager.getSnapshot().controller);
});

test('F. sucesso da carga atual termina em READY', async () => {
  const manager = createReplaySessionManager({
    createSession: async () => ({
      replayId: 'session-ok',
      status: 'READY',
      currentTimeMsc: 6_000_000,
    }),
    connect: (id, url, onClock, onError) => createMockController(id, onClock, onError),
  });

  const loadPromise = manager.load('2026-09-24');
  // Durante o carregamento:
  assert.equal(manager.getSnapshot().status, 'LOADING');

  await loadPromise;

  // Sucesso termina em READY:
  const snap = manager.getSnapshot();
  assert.equal(snap.status, 'READY');
  assert.equal(snap.clock, 6_000_000);
  assert.equal(snap.error, null);
  assert.ok(snap.controller);

  // Verificação das regras de habilitação de botões:
  const isLoading = snap.status === 'LOADING';
  const canPlay = !isLoading && !!snap.controller && (snap.status === 'READY' || snap.status === 'PAUSED');
  const canPause = !isLoading && !!snap.controller && snap.status === 'PLAYING';

  assert.equal(isLoading, false); // CARREGAR habilitado
  assert.equal(canPlay, true);    // PLAY habilitado
  assert.equal(canPause, false);  // PAUSE desabilitado
});

test('G. falha da carga atual libera CARREGAR, limpa dados e exibe erro', async () => {
  const manager = createReplaySessionManager({
    createSession: async () => {
      throw new Error('Falha ao carregar Replay.');
    },
    connect: (id, url, onClock, onError) => createMockController(id, onClock, onError),
  });

  await manager.load('2026-09-24');

  const snap = manager.getSnapshot();
  assert.equal(snap.status, 'ERROR');
  assert.equal(snap.error, 'Falha ao carregar Replay.');
  assert.equal(snap.controller, undefined);
  assert.equal(snap.clock, undefined);

  // Botões:
  const isLoading = snap.status === 'LOADING';
  assert.equal(isLoading, false); // CARREGAR liberado
});
