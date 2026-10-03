export type ReplayTradeEvent = {
  type: 'REPLAY_TRADE_EVENT'; eventId: string; tradeId: string;
  stage: 'ENTRY' | 'PARTIAL' | 'BREAKEVEN' | 'EXIT' | 'REJECTED'; symbol: string; side: 'BUY' | 'SELL';
  timeMsc: number; sequence: number; price: number; entry: number;
  initialStop: number; risk: number; stop: number; points: number | null; exit: string | null; reason?: string | null;
};
export type ReplayTradeMarker = ReplayTradeEvent & { time: number };

export function createReplayTradeStore() {
  const events = new Map<string, ReplayTradeMarker>();
  let publish: ((marker: ReplayTradeMarker) => void) | undefined;
  return {
    add(message: ReplayTradeEvent) {
      if (!message.eventId || !message.tradeId || events.has(message.eventId)
        || !Number.isSafeInteger(message.timeMsc) || !Number.isFinite(message.price)
        || !['ENTRY', 'PARTIAL', 'BREAKEVEN', 'EXIT', 'REJECTED'].includes(message.stage)
        || !['BUY', 'SELL'].includes(message.side)) return;
      const marker = { ...message, time: Math.floor(message.timeMsc / 300_000) * 300 };
      events.set(message.eventId, marker);
      publish?.(marker);
    },
    attach(next: (marker: ReplayTradeMarker) => void) {
      publish = next; events.forEach(next);
      return () => { if (publish === next) publish = undefined; };
    },
    clear() { events.clear(); publish = undefined; },
  };
}

export function tradeLabel(marker: ReplayTradeEvent) {
  if (marker.stage === 'REJECTED') return '';
  if (marker.stage === 'ENTRY') return marker.side === 'BUY' ? 'COMPRA' : 'VENDA';
  if (marker.stage === 'PARTIAL') return 'PARCIAL 50%';
  if (marker.stage === 'BREAKEVEN') return 'BE protegido';
  const points = marker.points ?? 0;
  return `${points === 0 ? '0x0' : points > 0 ? 'GAIN' : 'STOP'} ${points > 0 ? '+' : ''}${points} pts`;
}
