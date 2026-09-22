import type { Mt5Candle } from '@/features/win/api/mt5Api';
import type { LiveTick } from '@/features/win/api/mt5LiveConnection';

export const M5_SECONDS = 300;
export const RESYNC_RETRY_MS = 5000;
export const m5Bucket = (epochSeconds: number) => Math.floor(epochSeconds / M5_SECONDS) * M5_SECONDS;

type Change = { kind: 'ignored' | 'resync' } | { kind: 'updated'; candle: Mt5Candle };

export function updateLastCandle(candle: Mt5Candle, tick: LiveTick): Change {
  if (!Number.isFinite(tick.last) || tick.last <= 0
    || !Number.isSafeInteger(tick.timeMsc) || Math.floor(tick.timeMsc / 1000) !== tick.time) {
    return { kind: 'ignored' };
  }
  const bucket = m5Bucket(tick.timeMsc / 1000);
  const currentBucket = m5Bucket(candle.time);
  if (bucket < currentBucket) return { kind: 'ignored' };
  if (bucket > currentBucket) return { kind: 'resync' };
  return { kind: 'updated', candle: {
    ...candle,
    // Only the backend computes SMA. Match its raw bar timestamp before drawing.
    ...(tick.sma9Time === candle.time && typeof tick.sma9 === 'number' && Number.isFinite(tick.sma9)
      ? { sma9: tick.sma9 } : {}),
    ...(tick.sma21Time === candle.time && typeof tick.sma21 === 'number' && Number.isFinite(tick.sma21)
      ? { sma21: tick.sma21 } : {}),
    high: Math.max(candle.high, tick.last), low: Math.min(candle.low, tick.last), close: tick.last,
  } };
}

type ChartSink = {
  setHistory: (candles: Mt5Candle[]) => void;
  updateLast: (candle: Mt5Candle) => void;
};
type Snapshot = { loading: boolean; error: string | null; count: number };

/** Imperative chart updates; React subscribers are notified only about REST state. */
export function createMt5Intrabar(load: (signal: AbortSignal) => Promise<Mt5Candle[]>) {
  let history: Mt5Candle[] = [];
  let current: Mt5Candle | undefined;
  let latest: LiveTick | undefined;
  let chart: ChartSink | undefined;
  let active = false;
  let generation = 0;
  let request: AbortController | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let snapshot: Snapshot = { loading: false, error: null, count: 0 };
  const listeners = new Set<() => void>();

  function notify(loading: boolean, error: string | null) {
    snapshot = { loading, error, count: history.length };
    listeners.forEach(listener => listener());
  }

  function scheduleRetry() {
    if (!active || retry !== undefined) return;
    retry = setTimeout(() => { retry = undefined; void refresh(); }, RESYNC_RETRY_MS);
  }

  function applyLatest(): boolean {
    if (!current || !latest) return false;
    const change = updateLastCandle(current, latest);
    if (change.kind === 'updated') {
      current = change.candle;
      chart?.updateLast(current);
    }
    return change.kind === 'resync';
  }

  async function refresh() {
    if (!active || request) return;
    clearTimeout(retry);
    retry = undefined;
    const controller = new AbortController();
    request = controller;
    const version = generation;
    notify(true, null);
    try {
      const candles = await load(controller.signal);
      if (!active || version !== generation) return;
      if (!candles.length && history.length) throw new Error('MT5 retornou histórico vazio durante a sincronização.');
      history = candles;
      current = candles.at(-1);
      chart?.setHistory(candles);
      // Replay the newest tick, including one received before the initial REST completed.
      const behind = applyLatest();
      notify(false, behind ? 'Aguardando o candle M5 oficial do MT5. Nova tentativa em 5 segundos.' : null);
      if (behind) scheduleRetry();
    } catch (error) {
      if (!active || version !== generation) return;
      console.error('Falha ao sincronizar candles MT5', error);
      notify(false, history.length
        ? 'Falha ao sincronizar candles. Gráfico preservado; nova tentativa em 5 segundos.'
        : 'Não foi possível obter os candles do MT5. Nova tentativa em 5 segundos.');
      scheduleRetry();
    } finally {
      if (version === generation) request = undefined;
    }
  }

  return {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    getSnapshot: () => snapshot,
    start() { active = true; void refresh(); },
    stop() {
      active = false;
      generation++;
      request?.abort();
      request = undefined;
      clearTimeout(retry);
      retry = undefined;
    },
    refresh,
    receiveTick(tick: LiveTick) {
      if (!active || (latest && tick.timeMsc < latest.timeMsc)) return;
      if (!Number.isFinite(tick.last) || tick.last <= 0
        || !Number.isSafeInteger(tick.timeMsc) || Math.floor(tick.timeMsc / 1000) !== tick.time) return;
      latest = tick;
      if (applyLatest() && !request && retry === undefined) void refresh();
    },
    attachChart(sink: ChartSink) {
      chart = sink;
      if (history.length) {
        sink.setHistory(current ? [...history.slice(0, -1), current] : history);
      }
      return () => { if (chart === sink) chart = undefined; };
    },
  };
}

export type Mt5Intrabar = ReturnType<typeof createMt5Intrabar>;
