import type { Mt5Candle } from '@/features/win/api/mt5Api';
import { M5_SECONDS, type ChartSink } from '@/features/win/models/mt5Intrabar';
import { createReplayOccurrenceStore } from './replayOccurrenceStore';
import { createReplaySetupStore } from './replaySetupStore';

type Message = {
  type: 'CLOCK' | 'MARKET_STATE' | 'RULE_OCCURRENCE' | 'REPLAY_SETUP_EVENT' | 'COMPLETED' | 'ERROR';
  timeMsc: number;
  status: string;
  candle?: { time: number; open: number; high: number; low: number; close: number };
  sma9?: number | null;
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
  setupType?: 'CRZ09_UP' | 'RJ09_UP' | 'PULLB09_UP';
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
  let closed = false;

  const controller = {
    attachChart(next: ChartSink) {
      if (closed) return () => {};
      sink = next;
      if (history.length) next.setHistory(history);
      const detachOccurrences = occurrences.attach(marker => next.addRuleOccurrence?.(marker));
      const detachSetupEvents = setupEvents.attach(marker => next.addReplaySetupMarker?.(marker));
      return () => {
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
    const message = JSON.parse(event.data) as Message;
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
      && (message.setupType === 'CRZ09_UP' || message.setupType === 'RJ09_UP'
        || message.setupType === 'PULLB09_UP')) {
      setupEvents.add({
        type: 'REPLAY_SETUP_EVENT',
        eventId: message.eventId,
        timeMsc: message.timeMsc,
        candleTimeMsc: message.candleTimeMsc,
        setupType: message.setupType,
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
    last = candle;
    if (!previous || previous.time !== candle.time) history = [...history, candle];
    else history = [...history.slice(0, -1), candle];
    if (sink) {
      if (previous && candle.time !== previous.time) sink.setHistory(history);
      else sink.updateLast(candle);
    }
  };
  socket.onerror = () => {
    if (closed) return;
    onError('Não foi possível conectar ao Replay.');
  };
  return controller;
}

export type ReplayController = ReturnType<typeof connectReplay>;
