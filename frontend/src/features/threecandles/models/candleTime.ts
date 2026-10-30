export type CandleTimeBasis = 'MT5_WALL_CLOCK' | 'UTC_INSTANT';

/** UTC is only a field decoder for the terminal's civil clock, not a claim about UTC accuracy. */
export function candleDisplayZone(basis: CandleTimeBasis): string {
  return basis === 'MT5_WALL_CLOCK' ? 'UTC' : 'America/Sao_Paulo';
}

export function formatCandleTime(timeMsc: number, basis: CandleTimeBasis): string {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: candleDisplayZone(basis), dateStyle: 'short', timeStyle: 'medium',
  }).format(new Date(timeMsc));
}
