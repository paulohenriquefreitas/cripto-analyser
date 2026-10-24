import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { Alert, Button, LinearProgress, Link, Paper, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { createMt5Intrabar } from '@/features/win/models/mt5Intrabar';

import { getMt5Candles, getMt5LiveConfig } from '@/features/win/api/mt5Api';
import { Mt5LiveQuote } from '@/features/win/components/Mt5LiveQuote';
import { ReplayPage } from '@/features/replay/pages/ReplayPage';
import { WinM5MonitoredChart as Mt5CandlestickChart } from '@/features/win/components/WinM5MonitoredChart';
import { VolumeProfilePanel } from '@/features/win/components/VolumeProfilePanel';

export function WinMt5Page() {
  const [mode, setMode] = useState<'live' | 'replay'>('live');

  return (
    <Stack spacing={3}>
      <Typography variant="h3">WIN M5</Typography>
      <ToggleButtonGroup value={mode} exclusive size="small" aria-label="Modo do mercado"
        onChange={(_, nextMode: 'live' | 'replay' | null) => {
          if (nextMode) setMode(nextMode);
        }}>
        <ToggleButton value="live">Tempo real</ToggleButton>
        <ToggleButton value="replay">Replay</ToggleButton>
      </ToggleButtonGroup>
      {mode === 'live' ? <WinMt5LivePanel /> : <ReplayPage embedded setup91Only={false} />}
    </Stack>
  );
}

function WinMt5LivePanel() {
  const [symbol, setSymbol] = useState<string | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    void getMt5LiveConfig(abort.signal).then(config => { if (!abort.signal.aborted) setSymbol(config.symbol); })
      .catch(() => { if (!abort.signal.aborted) setError(true); });
    return () => abort.abort();
  }, []);
  if (error) return <Alert severity="error">Não foi possível confirmar o contrato MT5. Recarregue a página.</Alert>;
  return symbol ? <ConfiguredMt5LivePanel key={symbol} symbol={symbol} /> : <LinearProgress />;
}

function ConfiguredMt5LivePanel({ symbol }: { symbol: string }) {
  const [controller] = useState(() => createMt5Intrabar(signal => getMt5Candles(signal, symbol)));
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  useEffect(() => { controller.start(); return () => controller.stop(); }, [controller]);

  return (
    <Stack spacing={3}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
        <Typography variant="h6">{symbol} — M5</Typography>
        <Button variant="outlined" startIcon={<RefreshRoundedIcon />} disabled={state.loading}
          onClick={() => void controller.refresh()}>Atualizar</Button>
      </Stack>
      <Mt5LiveQuote onTick={controller.receiveTick} onFlow={controller.receiveFlow} symbol={symbol} />
      <VolumeProfilePanel symbol={symbol} />
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
          <Mt5CandlestickChart controller={controller} symbol={symbol} />
        </Paper>
      ) : null}
      <Typography variant="caption" color="text.secondary">
        Gráfico: <Link href="https://www.tradingview.com/" target="_blank" rel="noreferrer">
          TradingView Lightweight Charts™</Link> — Copyright (с) 2025 TradingView, Inc.
      </Typography>
    </Stack>
  );
}
