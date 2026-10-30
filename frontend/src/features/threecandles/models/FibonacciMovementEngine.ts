import type { CandleView } from '../api/threeCandlesApi';

export type FibAnchor = { id: string; symbol: string; timeframeMinutes: 5 | 60; type: 'TOP' | 'BOTTOM';
  price: number; extremeTimeMsc: number; confirmedAtMsc: number };
export type FibVersion = { id: string; mode: 'MANUAL' | 'EXPERIMENTAL'; timeframeMinutes: 5 | 60;
  decidedAtMsc: number; high: FibAnchor; low: FibAnchor; fib50: number; direction: 'UP' | 'DOWN';
  reason: string; previous: { highId: string; lowId: string; high: FibAnchor; low: FibAnchor } | null };
export type FibObservation = { id: string; atMsc: number; versionId: string; kind: string; detail: string; anchor?: FibAnchor };
export type FibCandle = { candle: { bucketStartTimeMsc: number; high: number; low: number; close: number };
  closed: boolean; availableAtMsc: number };
const order = (a: FibAnchor, b: FibAnchor) => a.confirmedAtMsc - b.confirmedAtMsc
  || a.extremeTimeMsc - b.extremeTimeMsc || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

export function fibVersion(high: FibAnchor, low: FibAnchor, at: number, mode: FibVersion['mode'],
  reason: string, previous: FibVersion | undefined, ordinal: number): FibVersion {
  if (!Number.isFinite(at) || high.type !== 'TOP' || low.type !== 'BOTTOM' || high.symbol !== low.symbol
    || high.timeframeMinutes !== low.timeframeMinutes || high.price <= low.price
    || high.extremeTimeMsc === low.extremeTimeMsc || high.confirmedAtMsc > at || low.confirmedAtMsc > at
    || high.confirmedAtMsc <= high.extremeTimeMsc || low.confirmedAtMsc <= low.extremeTimeMsc
    || !Number.isFinite(high.price) || !Number.isFinite(low.price)) throw new Error('Ancoras invalidas, de amplitudes distintas ou ainda nao confirmadas.');
  return { id: `${mode}:${high.timeframeMinutes}:${at}:${ordinal}`, mode, timeframeMinutes: high.timeframeMinutes,
    decidedAtMsc: at, high: { ...high }, low: { ...low }, fib50: (high.price + low.price) / 2,
    direction: high.extremeTimeMsc > low.extremeTimeMsc ? 'UP' : 'DOWN', reason,
    previous: previous ? { highId: previous.high.id, lowId: previous.low.id, high: { ...previous.high }, low: { ...previous.low } } : null };
}

/** Confirmed structural range, never a trade rule. A breakout in the opposite direction
 * starts a new leg from the previous terminal pivot. Midpoint interactions never do.
 */
export class FibonacciMovementEngine {
  readonly versions: FibVersion[] = [];
  readonly ambiguities: FibObservation[] = [];
  readonly events: FibObservation[] = [];
  private readonly seen = new Set<string>();
  private readonly pending: FibAnchor[] = [];
  readonly timeframeMinutes: 5 | 60;
  constructor(timeframeMinutes: 5 | 60) { this.timeframeMinutes = timeframeMinutes; }
  advance(anchors: readonly FibAnchor[], availableAt: number) {
    for (const anchor of anchors.filter(a => a.timeframeMinutes === this.timeframeMinutes
      && a.confirmedAtMsc <= availableAt).slice().sort(order)) {
      if (this.seen.has(anchor.id)) continue;
      this.seen.add(anchor.id);
      const current = this.versions.at(-1);
      if (!current) {
        const other = this.pending.find(a => a.type !== anchor.type && a.symbol === anchor.symbol
          && a.extremeTimeMsc !== anchor.extremeTimeMsc && (a.type === 'TOP' ? a.price > anchor.price : a.price < anchor.price));
        if (other) this.versions.push(fibVersion(anchor.type === 'TOP' ? anchor : other,
          anchor.type === 'BOTTOM' ? anchor : other, anchor.confirmedAtMsc, 'EXPERIMENTAL', 'INITIAL_OPPOSITE_PAIR_HYPOTHESIS', undefined, 1));
        else {
          if (this.pending.some(a => a.type !== anchor.type)) this.ambiguous(anchor, 'Par oposto sem ordem temporal/amplitude valida; aguardando outra ancora.');
          this.pending.push({ ...anchor });
        }
        continue;
      }
      if (anchor.symbol !== current.high.symbol) continue;
      const higher = anchor.type === 'TOP' && anchor.price > current.high.price;
      const lower = anchor.type === 'BOTTOM' && anchor.price < current.low.price;
      if (!higher && !lower) continue;
      const terminal = current.direction === 'UP' ? current.high : current.low;
      if (anchor.extremeTimeMsc <= terminal.extremeTimeMsc) {
        this.ambiguous(anchor, 'Extremo fora da amplitude com origem anterior ou simultanea ao extremo terminal; ordem estrutural indefinida.');
        continue;
      }
      const reversal = higher ? current.direction === 'DOWN' : current.direction === 'UP';
      const next = fibVersion(higher ? anchor : current.high, lower ? anchor : current.low,
        anchor.confirmedAtMsc, 'EXPERIMENTAL', reversal
          ? higher ? 'CONFIRMED_STRUCTURAL_REVERSAL_UP' : 'CONFIRMED_STRUCTURAL_REVERSAL_DOWN'
          : higher ? 'CONFIRMED_HIGHER_TOP_HYPOTHESIS' : 'CONFIRMED_LOWER_BOTTOM_HYPOTHESIS',
        current, this.versions.length + 1);
      this.versions.push(next);
      if (reversal) this.events.push({ id: `direction:${next.id}`, atMsc: next.decidedAtMsc,
        versionId: next.id, kind: 'DIRECTION_CHANGED', detail: `${current.direction} → ${next.direction}`, anchor: { ...anchor } });
      this.events.push({ id: `anchors:${next.id}`, atMsc: next.decidedAtMsc, versionId: next.id,
        kind: 'ANCHORS_UPDATED', detail: next.reason, anchor: { ...anchor } });
    }
  }
  private ambiguous(anchor: FibAnchor, detail: string) {
    this.ambiguities.push({ id: `ambiguity:${anchor.id}`, atMsc: anchor.confirmedAtMsc,
      versionId: this.versions.at(-1)?.id ?? '', kind: 'AMBIGUOUS_RANGE', detail, anchor: { ...anchor } });
  }
}

/** Geometric observations against a version already known at candle OPEN. No intrabar order is inferred. */
export function observeFibonacci(versions: readonly FibVersion[], candles: readonly FibCandle[], tolerance: number): FibObservation[] {
  if (!Number.isFinite(tolerance) || tolerance < 0) throw new Error('Tolerancia de observacao invalida.');
  const result: FibObservation[] = [];
  let previous: FibCandle | undefined;
  for (const bar of candles.filter(c => c.closed).slice().sort((a,b) => a.availableAtMsc - b.availableAtMsc)) {
    const v = versions.slice().reverse().find(v => v.decidedAtMsc <= bar.candle.bucketStartTimeMsc);
    if (v) {
      const { high, low, close } = bar.candle, mid = v.fib50;
      const add = (kind: string, detail: string) => result.push({ id: `${v.id}:${bar.availableAtMsc}:${kind}`,
        atMsc: bar.availableAtMsc, versionId: v.id, kind, detail });
      const test = low <= mid && high >= mid;
      const distance = Math.max(low - mid, mid - high, 0);
      if (test) add('TEST_50', 'O intervalo do candle inclui 50%; ordem intrabar desconhecida.');
      else if (distance <= tolerance) add('APPROACH_50', `Distancia do intervalo ao 50% de ate ${tolerance} pontos (parametro de observacao).`);
      if (previous && previous.availableAtMsc === bar.candle.bucketStartTimeMsc
        && v.decidedAtMsc <= previous.candle.bucketStartTimeMsc) {
        const before = previous.candle.close - mid, after = close - mid;
        if (before * after < 0) add('POSSIBLE_BREAK_50', 'Fechamentos consecutivos em lados opostos; nao confirma rompimento operacional.');
        else if (test && before * after > 0) add('POSSIBLE_REJECTION_50', 'Candle testa 50% e fecha no lado anterior; rejeicao apenas candidata.');
      }
    }
    previous = bar;
  }
  return result;
}

export function analyzeFibonacci(view: CandleView | undefined, tolerance = 5) {
  const cutoff = view?.availableAtMsc ?? view?.candles.at(-1)?.availableAtMsc ?? -1;
  const session = view?.structure?.preparation?.sessionDate ?? (cutoff >= 0 ? new Date(cutoff).toISOString().slice(0,10) : '');
  const preparation = view?.structure?.preparation;
  const source = view?.structure?.confirmedPivots ?? [...(view?.structure?.levels ?? []), ...(preparation?.candidates ?? [])];
  const additional = new Set(preparation?.experimental?.additionalCandidates.map(a => a.id) ?? []);
  const days = new Set([...(preparation?.sourceSessions ?? []), session]);
  const anchors: FibAnchor[] = [];
  const ids = new Set<string>();
  for (const p of source) {
    if (ids.has(p.id) || p.confirmedAtMsc > cutoff || additional.has(p.id)) continue;
    const day = new Date(p.extremeTimeMsc).toISOString().slice(0,10);
    if (p.timeframeMinutes === 5 ? day !== session : !days.has(day)) continue;
    ids.add(p.id);
    anchors.push({ id: p.id, symbol: view!.symbol, timeframeMinutes: p.timeframeMinutes, type: p.type,
      price: p.price, extremeTimeMsc: p.extremeTimeMsc, confirmedAtMsc: p.confirmedAtMsc });
  }
  anchors.sort(order);
  const candles = view?.candles.filter(c => c.closed && c.availableAtMsc <= cutoff) ?? [];
  const lanes = ([5,60] as const).map(tf => {
    const engine = new FibonacciMovementEngine(tf); engine.advance(anchors, cutoff);
    return { timeframeMinutes: tf, versions: engine.versions, ambiguities: engine.ambiguities, events: engine.events,
      observations: observeFibonacci(engine.versions, candles, tolerance) };
  });
  return { anchors, lanes, cutoff, session, fullMemory: !!view?.structure?.confirmedPivots };
}
