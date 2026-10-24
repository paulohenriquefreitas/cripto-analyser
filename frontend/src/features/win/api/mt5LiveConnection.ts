import { z } from 'zod';
import type { MonitoringFlowSnapshot } from '../models/monitoringFlow';

const nullableNumber = z.number().finite().nullable();
export const monitoringFlowSchema: z.ZodType<MonitoringFlowSnapshot> = z.object({
  type: z.literal('FLOW'), symbol: z.string(), timeMsc: z.number().int().safe(),
  lastTimeMsc: z.number().int().safe().nullable(), last: nullableNumber,
  volumeSource: z.literal('MT5_VOLUME_REAL'),
  windows: z.array(z.object({
    seconds: z.union([z.literal(5), z.literal(10), z.literal(30)]),
    fromTimeMsc: z.number().int().safe(), toTimeMsc: z.number().int().safe(),
    events: z.number().int().nonnegative(), buyVolume: z.number().finite().nonnegative(),
    sellVolume: z.number().finite().nonnegative(), unknownVolume: z.number().finite().nonnegative(),
    delta: z.number().finite(), buyPercent: nullableNumber, sellPercent: nullableNumber,
    unknownPercent: nullableNumber, lastDisplacement: nullableNumber,
    quality: z.enum(['GOOD', 'WARMING_UP', 'DISCONNECTED', 'INVALID_VOLUME', 'NO_TRADES',
      'STALE', 'HIGH_UNKNOWN', 'PRICE_UNAVAILABLE', 'UNVALIDATED_SYMBOL']),
    alert: z.literal('POSSIBLE_ABSORPTION').nullable(),
  })).length(3).refine(windows => new Set(windows.map(window => window.seconds)).size === 3),
});

const tickSchema = z.object({
  type: z.literal('tick'), symbol: z.string().regex(/^WIN[A-Z][0-9]{2}$/),
  time: z.number().int().safe(), timeMsc: z.number().int().safe(),
  last: z.number().finite(), bid: z.number().finite(), ask: z.number().finite(),
  volume: z.number().finite(),
  sma9: z.number().finite().nullable().optional(),
  sma21: z.number().finite().nullable().optional(),
  sma9Time: z.number().int().safe().nullable().optional(),
  sma21Time: z.number().int().safe().nullable().optional(),
});
const messageSchema = z.union([tickSchema, z.object({ type: z.literal('status'), available: z.boolean() }), monitoringFlowSchema]);
export type LiveTick = z.infer<typeof tickSchema>;
export type LiveState = {
  status: 'connecting' | 'waiting' | 'live' | 'disconnected' | 'unavailable';
  tick: LiveTick | null;
};

export function mt5SocketUrl(apiUrl: string, pageUrl: string): string {
  const url = new URL(apiUrl, pageUrl);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.pathname = `${url.pathname.replace(/\/$/, '')}/ws/mt5/ticks`;
  url.search = '';
  url.hash = '';
  return url.toString();
}

// Standalone connection lifecycle, testable with a fake socket and fake timers.
export function connectMt5Ticks(
  url: string,
  onState: (state: LiveState) => void,
  createSocket: (url: string) => WebSocket = (address) => new WebSocket(address),
  onFlow?: (snapshot: MonitoringFlowSnapshot | null) => void,
  expectedSymbol?: string,
): () => void {
  let disposed = false;
  let socket: WebSocket | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;

  function detach(current: WebSocket) {
    current.onopen = null;
    current.onmessage = null;
    current.onerror = null;
    current.onclose = null;
  }

  function reconnect() {
    if (disposed) return;
    onState({ status: 'disconnected', tick: null });
    onFlow?.(null);
    timer = setTimeout(connect, 3000);
  }

  function connect() {
    if (disposed) return;
    timer = undefined;
    onState({ status: 'connecting', tick: null });
    let current: WebSocket;
    try { current = createSocket(url); }
    catch { reconnect(); return; }
    socket = current;
    current.onopen = () => {
      if (!disposed) onState({ status: 'waiting', tick: null });
    };
    current.onmessage = (event) => {
      if (disposed) return;
      try {
        const message = messageSchema.parse(JSON.parse(event.data));
        if (message.type !== 'status' && expectedSymbol && message.symbol !== expectedSymbol)
          throw new Error('Contrato do stream difere da sessão do gráfico');
        if (message.type === 'FLOW') onFlow?.(message);
        else if (message.type === 'tick') onState({ status: 'live', tick: message });
        else onState({ status: message.available ? 'waiting' : 'unavailable', tick: null });
      } catch {
        onState({ status: 'disconnected', tick: null });
        onFlow?.(null);
        current.close();
      }
    };
    current.onerror = () => {
      if (!disposed) onState({ status: 'disconnected', tick: null });
      if (!disposed) onFlow?.(null);
      current.close();
    };
    current.onclose = () => {
      detach(current);
      if (socket === current) socket = null;
      reconnect();
    };
  }

  connect();
  return () => {
    disposed = true;
    clearTimeout(timer);
    if (socket) {
      detach(socket);
      socket.close();
      socket = null;
    }
  };
}
