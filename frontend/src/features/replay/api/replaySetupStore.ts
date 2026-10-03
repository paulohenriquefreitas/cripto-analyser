import type { ReplaySetupMarker } from '@/features/win/models/mt5Intrabar';

export type ReplaySetupEventMessage = {
  type: 'REPLAY_SETUP_EVENT';
  eventId: string;
  timeMsc: number;
  candleTimeMsc: number;
  setupType: ReplaySetupMarker['setupType'];
  signal?: ReplaySetupMarker['signal'];
  sequence?: number | null;
  breakoutPrice?: number | null;
};

export function isReplaySetupType(value: unknown): value is ReplaySetupMarker['setupType'] {
  return typeof value === 'string' && [
    'SETUP91_BUY_ARMED', 'SETUP91_BUY_TRIGGERED', 'SETUP91_BUY_CANCELLED',
    'SETUP91_SELL_ARMED', 'SETUP91_SELL_TRIGGERED', 'SETUP91_SELL_CANCELLED',
  ].includes(value);
}

export function createReplaySetupStore(bucketSeconds: number) {
  const events = new Map<string, ReplaySetupMarker>();
  let publish: ((marker: ReplaySetupMarker) => void) | undefined;

  return {
    add(message: ReplaySetupEventMessage) {
      if (!message.eventId || !Number.isSafeInteger(message.timeMsc)
        || !Number.isSafeInteger(message.candleTimeMsc)
        || !isReplaySetupType(message.setupType)
        || events.has(message.eventId)) return;
      // Presentation only: ARMED anchors to the signal; later events anchor to their LAST.
      const anchor = !message.setupType.endsWith('_ARMED') ? message.timeMsc : message.candleTimeMsc;
      const marker: ReplaySetupMarker = {
        eventId: message.eventId,
        time: Math.floor(anchor / (bucketSeconds * 1000)) * bucketSeconds,
        setupType: message.setupType,
        timeMsc: message.timeMsc, candleTimeMsc: message.candleTimeMsc,
        signal: message.signal, sequence: message.sequence, breakoutPrice: message.breakoutPrice,
      };
      events.set(marker.eventId, marker);
      publish?.(marker);
    },
    attach(next: (marker: ReplaySetupMarker) => void) {
      publish = next;
      events.forEach(next);
      return () => { if (publish === next) publish = undefined; };
    },
    clear() {
      events.clear();
      publish = undefined;
    },
  };
}
