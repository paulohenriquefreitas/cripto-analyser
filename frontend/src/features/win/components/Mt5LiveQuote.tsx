import { Chip, Paper, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { env } from '@/common/api/env';
import { connectMt5Ticks, mt5SocketUrl, type LiveState, type LiveTick } from '@/features/win/api/mt5LiveConnection';
import type { MonitoringFlowSnapshot } from '../models/monitoringFlow';

const labels = {
  connecting: 'CONECTANDO...', waiting: 'AGUARDANDO TICK', live: 'AO VIVO',
  disconnected: 'DESCONECTADO', unavailable: 'FEED MT5 INDISPONÍVEL',
} as const;
const price = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });
const time = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'UTC', hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
});

export function Mt5LiveQuote({ onTick, onFlow, symbol }: {
  onTick?: (tick: LiveTick) => void; onFlow?: (snapshot: MonitoringFlowSnapshot | null) => void;
  symbol?: string;
}) {
  const [state, setState] = useState<LiveState>({ status: 'connecting', tick: null });
  useEffect(() => connectMt5Ticks(mt5SocketUrl(env.apiUrl, window.location.href), (next) => {
    setState(next);
    if (next.tick) onTick?.(next.tick);
  }, undefined, onFlow, symbol), [onTick, onFlow, symbol]);

  return (
    <Paper sx={{ p: 2.5 }} data-testid="mt5-live-quote">
      <Stack spacing={2}>
        <Chip label={labels[state.status]} role="status" sx={{ alignSelf: 'flex-start' }}
          color={state.status === 'live' ? 'success' : state.status === 'unavailable' ? 'warning' : 'default'} />
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 5 }}>
          {([['Último', 'last'], ['Bid', 'bid'], ['Ask', 'ask']] as const).map(([label, field]) => (
            <Stack key={field}>
              <Typography color="text.secondary">{label}</Typography>
              <Typography variant="h4" data-testid={`mt5-${field}`} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {state.tick ? price.format(state.tick[field]) : '—'}
              </Typography>
            </Stack>
          ))}
        </Stack>
        <Typography variant="caption" color="text.secondary" data-testid="mt5-tick-time">
          {state.tick ? `${time.format(new Date(state.tick.timeMsc))} UTC` : 'Aguardando cotação do MT5.'}
        </Typography>
      </Stack>
    </Paper>
  );
}
