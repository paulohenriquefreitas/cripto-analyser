import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { Alert, Button, LinearProgress, Link, Paper, Stack, Typography } from '@mui/material';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { createMt5Intrabar } from '@/features/win/models/mt5Intrabar';

import { getMt5Candles } from '@/features/win/api/mt5Api';
import { Mt5LiveQuote } from '@/features/win/components/Mt5LiveQuote';
import { Mt5CandlestickChart } from '@/features/win/components/Mt5CandlestickChart';

export function WinMt5Page() {
  const [controller] = useState(() => createMt5Intrabar(signal => getMt5Candles(signal)));
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  useEffect(() => { controller.start(); return () => controller.stop(); }, [controller]);

  return (
    <Stack spacing={3}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
        <Typography variant="h3">WINV26 — M5</Typography>
        <Button variant="outlined" startIcon={<RefreshRoundedIcon />} disabled={state.loading}
          onClick={() => void controller.refresh()}>Atualizar</Button>
      </Stack>
      <Mt5LiveQuote onTick={controller.receiveTick} />
      <Typography color="text.secondary">
        Últimos 100 candles do MT5 · Horários em UTC · O último candle pode estar em formação.
      </Typography>
      {state.loading ? <Stack spacing={1}><LinearProgress />
        <Typography>Carregando candles do MT5...</Typography></Stack> : null}
      {state.error ? <Alert severity="error">{state.error}</Alert> : null}
      {!state.loading && !state.error && state.count === 0
        ? <Alert severity="info">Nenhum candle disponível.</Alert> : null}
      {state.count > 0 ? (
        <Paper sx={{ p: { xs: 1, md: 2 }, minWidth: 0 }}>
          <Mt5CandlestickChart controller={controller} />
        </Paper>
      ) : null}
      <Typography variant="caption" color="text.secondary">
        Gráfico: <Link href="https://www.tradingview.com/" target="_blank" rel="noreferrer">
          TradingView Lightweight Charts™</Link> — Copyright (с) 2025 TradingView, Inc.
      </Typography>
    </Stack>
  );
}
