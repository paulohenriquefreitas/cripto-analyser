import { createReplayTradeStore, type ReplayTradeEvent } from './replayTradeStore';
import type { Mt5Candle } from '@/features/win/api/mt5Api';
import { M5_SECONDS, type ChartSink, type Ema9ReversalMarker } from '@/features/win/models/mt5Intrabar';
import { createReplayOccurrenceStore } from './replayOccurrenceStore';
import { createReplaySetupStore, isReplaySetupType, type ReplaySetupEventMessage } from './replaySetupStore';

type Message = {
  type: 'CLOCK' | 'MARKET_STATE' | 'RULE_OCCURRENCE' | 'REPLAY_SETUP_EVENT' | 'COMPLETED' | 'ERROR';
  timeMsc: number;
  status: string;
  candle?: { time: number; open: number; high: number; low: number; close: number };
  sma9?: number | null;
  closedEma9?: { time: number; value: number; reversal?: 'UP' | 'DOWN' | null;
    previousValue?: number | null; availableAtTimeMsc?: number } | null;
  sma21?: number | null;
  vwap?: number | null;
  vwapSession?: string | null;
  message?: string;
  occurrenceId?: string;
  ruleId?: string;
  symbol?: string;
  price?: number;
  direction?: 'LONG' | 'SHORT';
  reference?: 'SMA21';
  approachSide?: 'ABOVE' | 'BELOW' | 'AT';
  exitSide?: 'ABOVE' | 'BELOW' | 'AT';
  eventId?: string;
  candleTimeMsc?: number;
  setupType?: ReplaySetupEventMessage['setupType'];
  signal?: ReplaySetupEventMessage['signal'];
  sequence?: number | null;
  breakoutPrice?: number | null;
};

export function connectReplay(
  replayId: string,
  pageUrl: string,
  onClock: (timeMsc: number, status: string) => void,
  onError: (message: string) => void,
  createSocket: (url: string) => WebSocket = (url: string) => new WebSocket(url),
) {
  const url = new URL(`/ws/replay/${replayId}`, pageUrl);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = createSocket(url.toString());
  const pendingCommands: string[] = [];
  let sink: ChartSink | undefined;
  let last: Mt5Candle | undefined;
  let history: Mt5Candle[] = [];
  const occurrences = createReplayOccurrenceStore(M5_SECONDS);
  const setupEvents = createReplaySetupStore(M5_SECONDS);
  const tradeEvents = createReplayTradeStore();
  const emaReversals = new Map<number, Ema9ReversalMarker>();
  let closed = false;

  const controller = {
    attachChart(next: ChartSink) {
      if (closed) return () => {};
      sink = next;
      if (history.length) next.setHistory(history);
      emaReversals.forEach(marker => next.addEma9Reversal?.(marker));
      const detachOccurrences = occurrences.attach(marker => next.addRuleOccurrence?.(marker));
      const detachSetupEvents = setupEvents.attach(marker => next.addReplaySetupMarker?.(marker));
      const detachTrades = tradeEvents.attach(marker => next.addReplayTradeMarker?.(marker));
      return () => {
        detachTrades();
        detachOccurrences();
        detachSetupEvents();
        if (sink === next) sink = undefined;
      };
    },
    play() {
      if (closed) return;
      if (socket.readyState === WebSocket.OPEN) socket.send('PLAY');
      else if (socket.readyState === WebSocket.CONNECTING) pendingCommands.push('PLAY');
    },
    pause() {
      if (closed) return;
      if (socket.readyState === WebSocket.OPEN) socket.send('PAUSE');
      else if (socket.readyState === WebSocket.CONNECTING) pendingCommands.push('PAUSE');
    },
    close() {
      if (closed) return;
      closed = true;
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      pendingCommands.length = 0;
      occurrences.clear();
      setupEvents.clear();
      tradeEvents.clear();
      emaReversals.clear();
      history = [];
      last = undefined;
      if (sink) {
        sink.setHistory([]);
        sink = undefined;
      }
      try {
        socket.close();
      } catch {
        // ignore socket close errors
      }
    },
    isClosed() {
      return closed;
    },
  };
  socket.onopen = () => {
    if (closed) return;
    for (const command of pendingCommands.splice(0)) socket.send(command);
  };
  socket.onmessage = event => {
    if (closed) return;
    const parsed = JSON.parse(event.data);
    if (parsed.type === 'REPLAY_TRADE_EVENT') {
      tradeEvents.add(parsed as ReplayTradeEvent);
      return;
    }
    const message = parsed as Message;
    if (typeof message.status === 'string') onClock(message.timeMsc, message.status);
    if (message.type === 'ERROR') onError(message.message ?? 'Replay error');
    if (message.type === 'RULE_OCCURRENCE'
      && typeof message.occurrenceId === 'string'
      && (message.direction === 'LONG' || message.direction === 'SHORT')) {
      occurrences.add({
        type: 'RULE_OCCURRENCE',
        occurrenceId: message.occurrenceId,
        timeMsc: message.timeMsc,
        direction: message.direction,
      });
      return;
    }
    if (message.type === 'REPLAY_SETUP_EVENT'
      && typeof message.eventId === 'string'
      && typeof message.candleTimeMsc === 'number'
      && Number.isSafeInteger(message.candleTimeMsc)
      && isReplaySetupType(message.setupType)) {
      setupEvents.add({
        type: 'REPLAY_SETUP_EVENT',
        eventId: message.eventId,
        timeMsc: message.timeMsc,
        candleTimeMsc: message.candleTimeMsc,
        setupType: message.setupType,
        signal: message.signal,
        sequence: message.sequence,
        breakoutPrice: message.breakoutPrice,
      });
      return;
    }
    if (message.type !== 'MARKET_STATE' || !message.candle) return;
    const candle: Mt5Candle = {
      ...message.candle, tickVolume: 0, realVolume: 0,
      sma9: message.sma9 ?? null, sma21: message.sma21 ?? null,
      vwap: message.vwap ?? null, vwapSession: message.vwapSession ?? undefined,
    };
    const previous = last;
    // The backend publishes EMA only after closure, keyed to the closed candle.
    const ema = message.closedEma9;
    if (ema && Number.isFinite(ema.value)) {
      history = history.map(bar => bar.time === ema.time ? { ...bar, ema9: ema.value } : bar);
    }
    last = candle;
    if (!previous || previous.time !== candle.time) history = [...history, candle];
    else history = [...history.slice(0, -1), candle];
    if (sink) {
      if (previous && candle.time !== previous.time) sink.setHistory(history);
      else sink.updateLast(candle);
    }
    if (ema && (ema.reversal === 'UP' || ema.reversal === 'DOWN')
      && Number.isSafeInteger(ema.time) && ema.time < candle.time
      && Number.isFinite(ema.value) && typeof ema.previousValue === 'number'
      && Number.isFinite(ema.previousValue) && typeof ema.availableAtTimeMsc === 'number'
      && Number.isSafeInteger(ema.availableAtTimeMsc)
      && ema.availableAtTimeMsc >= (ema.time + M5_SECONDS) * 1000
      && !emaReversals.has(ema.time)) {
      const marker: Ema9ReversalMarker = { time: ema.time, direction: ema.reversal,
        value: ema.value, previousValue: ema.previousValue, availableAtTimeMsc: ema.availableAtTimeMsc };
      emaReversals.set(marker.time, marker);
      sink?.addEma9Reversal?.(marker);
    }
  };
  socket.onerror = () => {
    if (closed) return;
    onError('Não foi possível conectar ao Replay.');
  };
  return controller;
}

export type ReplayController = ReturnType<typeof connectReplay>;
