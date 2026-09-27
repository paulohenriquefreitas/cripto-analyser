import type { ReplaySetupMarker } from '@/features/win/models/mt5Intrabar';

export type ReplaySetupEventMessage = {
  type: 'REPLAY_SETUP_EVENT';
  eventId: string;
  timeMsc: number;
  candleTimeMsc: number;
  setupType: 'CRZ09_UP' | 'RJ09_UP' | 'PULLB09_UP';
};

export function createReplaySetupStore(bucketSeconds: number) {
  const events = new Map<string, ReplaySetupMarker>();
  let publish: ((marker: ReplaySetupMarker) => void) | undefined;

  return {
    add(message: ReplaySetupEventMessage) {
      if (!message.eventId || !Number.isSafeInteger(message.timeMsc)
        || !Number.isSafeInteger(message.candleTimeMsc)
        || !['CRZ09_UP', 'RJ09_UP', 'PULLB09_UP'].includes(message.setupType)
        || events.has(message.eventId)) return;
      const marker = {
        eventId: message.eventId,
        time: Math.floor(message.candleTimeMsc / (bucketSeconds * 1000)) * bucketSeconds,
        setupType: message.setupType,
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
