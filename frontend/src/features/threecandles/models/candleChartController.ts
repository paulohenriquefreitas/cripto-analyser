import type { ChartSink, StructuralChartLevel } from '@/features/win/models/mt5Intrabar';
import type { Mt5Candle } from '@/features/win/api/mt5Api';
import type { CandleView } from '../api/threeCandlesApi';
import { groupM5ForDisplay, m5GroupLine } from './m5DisplayGroups';

/** Only visible server candles enter this store. Same-candle updates stay incremental. */
export function createCandleChartController() {
  let bars: Mt5Candle[] = [];
  let identity = '';
  let levels: readonly StructuralChartLevel[] = [];
  let sink: ChartSink | undefined;
  let visibility = { m5: false, h1: true };
  let opening: number | undefined;
  let groupingPoints = 30;
  let separateMovements = false;
  let availableAt = -1;
  let additionalIds = new Set<string>();
  let hasComparison = false;
  let comparisonVisibility = { strict: true, additional: true };
  const visibleLevels = () => {
    const tolerance = identity.startsWith('WIN') ? groupingPoints : 0;
    const m5 = visibility.m5 ? groupM5ForDisplay(levels, tolerance, availableAt, separateMovements).map(m5GroupLine) : [];
    const h1 = levels.filter(l => l.timeframeMinutes === 60 && visibility.h1
      && (!hasComparison || (additionalIds.has(l.id) ? comparisonVisibility.additional : comparisonVisibility.strict)));
    return [...m5, ...h1];
  };
  const publish = () => sink?.setStructureLevels?.(visibleLevels());
  return {
    attachChart(next: ChartSink) {
      sink = next; next.setHistory(bars);
      if (opening !== undefined) next.focusTime?.(opening);
      next.setStructureLevels?.(visibleLevels());
      return () => { if (sink === next) sink = undefined; };
    },
    setM5Grouping(points: number, separate = false) {
      if (!Number.isFinite(points) || points < 0) throw new Error('Tolerancia M5 invalida.');
      groupingPoints = points; separateMovements = separate; publish();
    },
    fitChart() { if (bars.length) sink?.focusTime?.(bars.at(-1)!.time); },
    setVisibility(m5: boolean, h1: boolean) { visibility = { m5, h1 }; publish(); },
    setH1ComparisonVisibility(strict: boolean, additional: boolean) { comparisonVisibility = { strict, additional }; publish(); },
    reset() { availableAt = -1; identity = ''; bars = []; levels = []; opening = undefined; additionalIds.clear(); hasComparison = false; sink?.setHistory([]); sink?.setStructureLevels?.([]); },
    apply(view: CandleView) {
      const nextIdentity = `${view.symbol}:${view.timeframeMinutes}`;
      const next = view.candles.map(({ candle: c }) => ({ time: c.bucketStartTimeMsc / 1000,
        open: c.open, high: c.high, low: c.low, close: c.close, tickVolume: 0, realVolume: 0 }));
      if (next.some((c, i) => !Number.isSafeInteger(c.time) || c.time % (view.timeframeMinutes * 60) !== 0
        || i > 0 && c.time <= next[i-1].time)) throw new Error('Candles fora de ordem ou período inconsistente.');
      const incremental = identity === nextIdentity && (next.length === bars.length || next.length === bars.length + 1) && bars.length > 0
        && next.every((bar, i) => i === next.length-1 ? (!bars[i] || bar.time === bars[i].time)
          : (['time', 'open', 'high', 'low', 'close'] as const).every(k => bar[k] === bars[i][k]));
      bars = next; identity = nextIdentity;
      if (incremental) sink?.updateLast(next.at(-1)!);
      else sink?.setHistory(next);
      const nextOpening = view.id ? view.structure?.preparation?.sessionOpenMsc ?? view.candles[0]?.candle.bucketStartTimeMsc : undefined;
      if (nextOpening !== undefined && bars.length > 0 && opening !== nextOpening / 1000) {
        opening = nextOpening / 1000;
        sink?.focusTime?.(opening);
      }
      availableAt = view.availableAtMsc ?? view.candles.at(-1)?.availableAtMsc ?? -1;
      levels = view.timeframeMinutes === 5 ? view.structure?.levels ?? [] : [];
      additionalIds = new Set(view.structure?.preparation?.experimental?.additionalCandidates.map(l=>l.id) ?? []);
      hasComparison = !!view.structure?.preparation?.experimental;
      publish();
    },
  };
}
