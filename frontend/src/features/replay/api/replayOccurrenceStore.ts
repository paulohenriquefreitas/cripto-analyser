import type { ReplayRuleMarker } from '@/features/win/models/mt5Intrabar';

export type RuleOccurrenceMessage = {
  type: 'RULE_OCCURRENCE';
  occurrenceId: string;
  timeMsc: number;
  direction: 'LONG' | 'SHORT';
};

export function createReplayOccurrenceStore(bucketSeconds: number) {
  const occurrences = new Map<string, ReplayRuleMarker>();
  let publish: ((marker: ReplayRuleMarker) => void) | undefined;

  return {
    add(message: RuleOccurrenceMessage) {
      if (!message.occurrenceId || !Number.isSafeInteger(message.timeMsc)
        || (message.direction !== 'LONG' && message.direction !== 'SHORT')
        || occurrences.has(message.occurrenceId)) return;
      const marker = {
        occurrenceId: message.occurrenceId,
        time: Math.floor(message.timeMsc / (bucketSeconds * 1000)) * bucketSeconds,
        direction: message.direction,
      };
      occurrences.set(marker.occurrenceId, marker);
      publish?.(marker);
    },
    attach(next: (marker: ReplayRuleMarker) => void) {
      publish = next;
      occurrences.forEach(next);
      return () => { if (publish === next) publish = undefined; };
    },
    clear() {
      occurrences.clear();
      publish = undefined;
    },
  };
}
