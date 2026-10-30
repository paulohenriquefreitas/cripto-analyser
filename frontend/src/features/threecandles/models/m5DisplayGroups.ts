import type { StructuralChartLevel } from '@/features/win/models/mt5Intrabar';

export type M5DisplayPivot = StructuralChartLevel & {
  state?: 'CONFIRMED' | 'BROKEN';
  brokenAtMsc?: number | null;
};
export type M5VisualReplacement = {
  oldPivot: M5DisplayPivot;
  newPivot: M5DisplayPivot;
  distancePoints: number;
  atMsc: number;
  reason: 'SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE';
};
export type M5DisplayGroup = {
  id: string;
  type: M5DisplayPivot['type'];
  price: number;
  low: number;
  high: number;
  members: readonly M5DisplayPivot[];
  movementCount: number;
  replacements: readonly M5VisualReplacement[];
};

const symbol = (level: M5DisplayPivot) => level.id.includes(':5:') ? level.id.split(':5:')[0] : '';
const order = (a: M5DisplayPivot, b: M5DisplayPivot) => a.confirmedAtMsc - b.confirmedAtMsc
  || a.extremeTimeMsc - b.extremeTimeMsc || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
const brokenBefore = (member: M5DisplayPivot, next: M5DisplayPivot) => member.brokenAtMsc != null
  && member.brokenAtMsc <= next.extremeTimeMsc;

/** Display only: replay confirmations in ascending (confirmation, formation, ID) order.
 * For simultaneous confirmations, the greatest formation timestamp, then greatest ID wins.
 * Compare only currently represented prices, across types; hidden members never reactivate.
 * Breaks remain audit data and never exempt a nearby line from replacement.
 */
export function groupM5ForDisplay(
  levels: readonly M5DisplayPivot[], tolerance: number, availableAtMsc = Infinity, _separateMovements = false,
): M5DisplayGroup[] {
  if (!Number.isFinite(tolerance) || tolerance < 0) throw new Error('Tolerancia M5 deve ser finita e nao negativa.');
  const groups: M5DisplayGroup[] = [];
  const ids = new Set<string>();
  const pivots = levels.filter(l => (l.timeframeMinutes ?? 5) === 5 && l.confirmedAtMsc <= availableAtMsc).slice().sort(order);
  for (const pivot of pivots) {
    if (ids.has(pivot.id)) continue;
    ids.add(pivot.id);
    // Zero explicitly disables compaction, including for equal prices.
    const replaced = tolerance === 0 ? [] : groups.filter(g =>
      symbol(m5GroupLine(g)) === symbol(pivot) && Math.abs(g.price - pivot.price) <= tolerance);
    const members = [...replaced.flatMap(g => g.members), pivot].sort(order);
    const replacements = [...replaced.flatMap(g => g.replacements), ...replaced.map(g => ({
      oldPivot: m5GroupLine(g), newPivot: pivot, distancePoints: Math.abs(g.price - pivot.price),
      atMsc: pivot.confirmedAtMsc, reason: 'SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE' as const,
    }))].sort((a,b) => a.atMsc - b.atMsc || order(a.newPivot,b.newPivot) || order(a.oldPivot,b.oldPivot));
    for (const previous of replaced) groups.splice(groups.indexOf(previous),1);
    groups.push({ id: `m5-region:${pivot.id}`, type: pivot.type, price: pivot.price,
      low: Math.min(...members.map(m => m.price)), high: Math.max(...members.map(m => m.price)),
      members, movementCount: 1, replacements });
  }
  for (const group of groups) {
    let episode: M5DisplayPivot[] = [];
    for (const pivot of group.members) {
      if (episode.some(m => brokenBefore(m, pivot))) { group.movementCount++; episode = []; }
      episode.push(pivot);
    }
  }
  return groups;
}

/** Only chart delivery uses representative levels. Original pivots are never replaced. */
export function m5GroupLine(group: M5DisplayGroup): StructuralChartLevel {
  return group.members[group.members.length - 1]; // Exact latest pivot, with original type and chart ID.
}
