import type { SwingPivot } from './mt5SwingPivots';
import type { MonitoringFlowSnapshot } from './monitoringFlow';

export type MonitoringConfig = { activation: number; deactivation: number };
export type MonitoredLevel = { id: string; type: 'TOP' | 'BOTTOM'; price: number; pivotTime: number;
  distance: number | null; state: 'WAITING' | 'MONITORING';
  startedAt?: number; baseline?: MonitoringFlowSnapshot };
export function validMonitoringConfig(config: MonitoringConfig): boolean {
  return Number.isFinite(config.activation) && config.activation >= 0
    && Number.isFinite(config.deactivation) && config.deactivation > config.activation;
}
export function createSwingLevelMonitoring(initial: MonitoringConfig = { activation: 100, deactivation: 150 }) {
  if (!validMonitoringConfig(initial)) throw new Error('Invalid monitoring thresholds');
  let config = { ...initial };
  let last: number | undefined;
  let lastTime = -Infinity;
  let flow: MonitoringFlowSnapshot | null = null;
  const levels = new Map<string, MonitoredLevel>();
  const listeners = new Set<() => void>();
  let snapshot: { levels: readonly MonitoredLevel[]; last: number | null; config: MonitoringConfig; flow: MonitoringFlowSnapshot | null } = {
    levels: [], last: null, config, flow,
  };
  function publish() {
    snapshot = { levels: [...levels.values()], last: last ?? null, config, flow };
    listeners.forEach(listener => listener());
  }
  function evaluate(baseline = flow) {
    if (last === undefined) return;
    for (const [id, level] of levels) {
      const distance = Math.abs(last - level.price);
      const state = level.state === 'MONITORING'
        ? distance > config.deactivation ? 'WAITING' : 'MONITORING'
        : distance <= config.activation ? 'MONITORING' : 'WAITING';
      const entering = state === 'MONITORING' && level.state !== 'MONITORING';
      levels.set(id, { ...level, distance, state,
        ...(entering ? { startedAt: lastTime, baseline: baseline && baseline.timeMsc <= lastTime ? baseline : undefined } : {}),
        ...(state === 'WAITING' ? { startedAt: undefined, baseline: undefined } : {}),
      });
    }
  }
  return {
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    getSnapshot: () => snapshot,
    receiveFlow(next: MonitoringFlowSnapshot | null) {
      if (next && flow && next.timeMsc < flow.timeMsc) return;
      const previous = flow;
      flow = next;
      // Actual T&T LAST can arrive before the quote/candle producer. Never use the snapshot's
      // wall-clock time as the price timestamp or count a trade again in each monitored level.
      if (next?.last != null && next.lastTimeMsc != null && next.lastTimeMsc >= lastTime) {
        last = next.last; lastTime = next.lastTimeMsc;
        evaluate(previous ?? next);
      }
      publish();
    },
    addConfirmed(pivots: readonly SwingPivot[]) {
      for (const pivot of pivots) {
        const id = `${pivot.type}:${pivot.time}`;
        if (!levels.has(id)) levels.set(id, { id, type: pivot.type === 'HIGH' ? 'TOP' : 'BOTTOM',
          price: pivot.price, pivotTime: pivot.time, distance: null, state: 'WAITING' });
      }
      evaluate(); publish();
    },
    receiveLast(price: number, timeMsc: number) {
      if (!Number.isFinite(price) || price <= 0 || !Number.isSafeInteger(timeMsc) || timeMsc < lastTime) return;
      last = price; lastTime = timeMsc; evaluate(); publish();
    },
    configure(next: MonitoringConfig) {
      if (!validMonitoringConfig(next)) throw new Error('Invalid monitoring thresholds');
      config = { ...next }; evaluate(); publish();
    },
    reset() { levels.clear(); last = undefined; lastTime = -Infinity; flow = null; publish(); },
  };
}
export type SwingLevelMonitoring = ReturnType<typeof createSwingLevelMonitoring>;
