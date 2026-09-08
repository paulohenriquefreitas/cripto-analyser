import type { ChipProps } from '@mui/material';

import type { EntrySetupType, EntryStatus, MomentumStatus, Trend } from './scannerModels';

export const momentumStatusColor: Record<MomentumStatus, ChipProps['color']> = {
  QUALIFIED: 'success',
  WATCH: 'warning',
  REJECTED: 'default',
};

export const entryStatusColor: Record<EntryStatus, ChipProps['color']> = {
  BREAKOUT_READY: 'info',
  PULLBACK_READY: 'success',
  ENTRY_READY: 'success',
  PULLBACK_CONFIRMED: 'success',
  BREAKOUT_CONFIRMED: 'info',
  PULLBACK_IN_PROGRESS: 'warning',
  WATCH: 'default',
  WAIT_PULLBACK: 'warning',
  WAIT_BREAKOUT: 'warning',
  POTENTIAL_BREAKOUT: 'warning',
  OVEREXTENDED: 'error',
  TREND_WEAKENING: 'error',
  INVALIDATED: 'error',
  NO_ENTRY_SETUP: 'default',
};

export const entryStatusRowClass: Record<EntryStatus, string> = {
  BREAKOUT_READY: 'row-breakout-ready',
  PULLBACK_READY: 'row-pullback-ready',
  ENTRY_READY: 'row-entry-ready',
  PULLBACK_CONFIRMED: 'row-pullback-confirmed',
  BREAKOUT_CONFIRMED: 'row-breakout-confirmed',
  PULLBACK_IN_PROGRESS: 'row-pullback-progress',
  WATCH: 'row-watch',
  WAIT_PULLBACK: 'row-wait-pullback',
  WAIT_BREAKOUT: 'row-wait-breakout',
  POTENTIAL_BREAKOUT: 'row-wait-breakout',
  OVEREXTENDED: 'row-overextended',
  TREND_WEAKENING: 'row-trend-weakening',
  INVALIDATED: 'row-invalidated',
  NO_ENTRY_SETUP: 'row-no-entry',
};

export const setupTypes: EntrySetupType[] = ['PULLBACK', 'BREAKOUT', 'NONE'];
export const rankingModes = ['MOMENTUM', 'ENTRY', 'BREAKOUT', 'PULLBACK'] as const;
export const trendColor: Record<Trend, ChipProps['color']> = {
  UPTREND: 'success',
  DOWNTREND: 'error',
  SIDEWAYS: 'warning',
};
