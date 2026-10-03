import type { ReplayController } from './replayConnection';

export type ReplaySessionStatus = 'READY' | 'LOADING' | 'PLAYING' | 'PAUSED' | 'COMPLETED' | 'ERROR';

export type ReplaySessionSnapshot = {
  status: ReplaySessionStatus;
  clock?: number;
  error: string | null;
  controller?: ReplayController;
  loadGeneration: number;
};

export type ConnectReplayFn = (
  replayId: string,
  apiUrl: string,
  onClock: (timeMsc: number, status: string) => void,
  onError: (message: string) => void,
) => ReplayController;

export type CreateSessionFn = (
  symbol: string,
  date: string,
  signal?: AbortSignal,
) => Promise<{
  replayId: string;
  status: string;
  currentTimeMsc: number;
}>;

export type ReplaySessionManagerOptions = {
  symbol?: string;
  apiUrl?: string;
  createSession?: CreateSessionFn;
  connect?: ConnectReplayFn;
};

export function createReplaySessionManager(options: ReplaySessionManagerOptions = {}) {
  const symbol = options.symbol ?? 'WINV26';
  const apiUrl = options.apiUrl ?? '';
  let createSession = options.createSession;
  const connect = options.connect;

  let loadGeneration = 0;
  let activeController: ReplayController | undefined;
  let abortController: AbortController | undefined;
  let isLoading = false;
  let lastLoadTime = 0;
  const DOUBLE_CLICK_WINDOW_MS = 300;

  let snapshot: ReplaySessionSnapshot = {
    status: 'READY',
    clock: undefined,
    error: null,
    controller: undefined,
    loadGeneration: 0,
  };

  const listeners = new Set<(snapshot: ReplaySessionSnapshot) => void>();

  function notify() {
    for (const listener of listeners) {
      listener(snapshot);
    }
  }

  async function load(date: string, loadOptions: { force?: boolean; symbol?: string } = {}): Promise<void> {
    const now = Date.now();
    const isDoubleClick = now - lastLoadTime < DOUBLE_CLICK_WINDOW_MS;
    lastLoadTime = now;

    // 4. Clique duplo não pode iniciar duas cargas/sessões
    if (isDoubleClick && !loadOptions.force) {
      return;
    }
    if (isLoading && !loadOptions.force) {
      return;
    }

    isLoading = true;

    // 6. Proteção por geração/identidade da carga
    loadGeneration++;
    const thisGeneration = loadGeneration;

    // Abort pending HTTP request if any
    abortController?.abort();
    abortController = new AbortController();
    const signal = abortController.signal;

    // 5. Antes da nova carga: encerrar Replay anterior, fechar WebSocket anterior, remover listeners/callbacks antigos
    if (activeController) {
      activeController.close();
      activeController = undefined;
    }

    // 1. Ao clicar CARREGAR, limpar IMEDIATAMENTE a sessão visual anterior:
    // candles, MM9, MM21, VWAP, CRZ09, RJ09, PULLB09, relógio, erros anteriores, stores
    // 3. Enquanto estiver carregando: status = LOADING
    snapshot = {
      status: 'LOADING',
      clock: undefined,
      error: null,
      controller: undefined,
      loadGeneration: thisGeneration,
    };
    notify();

    try {
      if (!createSession) {
        throw new Error('createSession function is not configured.');
      }
      const session = await createSession(loadOptions.symbol ?? symbol, date, signal);
      // If a newer load has started, ignore response A completely!
      if (thisGeneration !== loadGeneration) {
        return;
      }

      if (!connect) {
        throw new Error('connect function is not configured.');
      }
      const nextController = connect(
        session.replayId,
        apiUrl,
        (timeMsc, nextStatus) => {
          if (thisGeneration !== loadGeneration) return;
          snapshot = {
            ...snapshot,
            clock: timeMsc,
            status: nextStatus as ReplaySessionStatus,
          };
          notify();
        },
        (errorMessage) => {
          if (thisGeneration !== loadGeneration) return;
          snapshot = {
            ...snapshot,
            status: 'ERROR',
            error: errorMessage,
          };
          notify();
        }
      );

      activeController = nextController;
      isLoading = false;

      // 7. Se a carga atual terminar com sucesso: status = READY
      snapshot = {
        status: 'READY',
        clock: session.currentTimeMsc > 0 ? session.currentTimeMsc : undefined,
        error: null,
        controller: nextController,
        loadGeneration: thisGeneration,
      };
      notify();
    } catch (cause) {
      // 6 & 8. Ignorar se não for a carga atual; mostrar erro somente se pertencer à carga atual
      if (thisGeneration !== loadGeneration) {
        return;
      }
      isLoading = false;
      if (activeController) {
        activeController.close();
        activeController = undefined;
      }
      snapshot = {
        status: 'ERROR',
        clock: undefined,
        error: cause instanceof Error ? cause.message : 'Falha ao carregar Replay.',
        controller: undefined,
        loadGeneration: thisGeneration,
      };
      notify();
    }
  }

  function play() {
    activeController?.play();
  }

  function pause() {
    activeController?.pause();
  }

  function close() {
    abortController?.abort();
    abortController = undefined;
    if (activeController) {
      activeController.close();
      activeController = undefined;
    }
    listeners.clear();
  }

  return {
    subscribe(listener: (snapshot: ReplaySessionSnapshot) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getSnapshot: () => snapshot,
    load,
    play,
    pause,
    close,
    setCreateSession(fn: CreateSessionFn) {
      createSession = fn;
    },
  };
}

export type ReplaySessionManager = ReturnType<typeof createReplaySessionManager>;
