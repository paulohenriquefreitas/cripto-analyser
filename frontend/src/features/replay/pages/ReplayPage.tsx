import { Alert, Box, Button, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { env } from '@/common/api/env';
import { createReplay } from '@/features/replay/api/replayApi';
import { connectReplay } from '@/features/replay/api/replayConnection';
import { createReplaySessionManager } from '@/features/replay/api/replaySessionManager';
import { Mt5CandlestickChart } from '@/features/win/components/Mt5CandlestickChart';

export function ReplayPage() {
  const [symbol, setSymbol] = useState('WIN$N');
  const [date, setDate] = useState('2026-09-24');
  const managerRef = useRef<ReturnType<typeof createReplaySessionManager> | null>(null);
  if (!managerRef.current) {
    managerRef.current = createReplaySessionManager({
      symbol: 'WINV26',
      apiUrl: env.apiUrl,
      createSession: createReplay,
      connect: connectReplay,
    });
  }
  const manager = managerRef.current;
  const state = useSyncExternalStore(manager.subscribe, manager.getSnapshot);

  useEffect(() => () => manager.close(), [manager]);

  const isLoading = state.status === 'LOADING';
  const canPlay = !isLoading && !!state.controller && (state.status === 'READY' || state.status === 'PAUSED');
  const canPause = !isLoading && !!state.controller && state.status === 'PLAYING';

  return (
    <Stack spacing={3}>
      <Typography variant="h3">Replay M5</Typography>
      <Stack direction="row" spacing={2} alignItems="center">
        <TextField select label="Ativo" value={symbol} disabled={isLoading}
          onChange={event => setSymbol(event.target.value)} sx={{ minWidth: 220 }}>
          <MenuItem value="WIN$N">WIN cont?nuo sem ajuste</MenuItem>
          <MenuItem value="WINV26">WINV26</MenuItem>
        </TextField>
        <TextField
          label="Data"
          type="date"
          value={date}
          onChange={event => setDate(event.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <Button
          variant="contained"
          disabled={isLoading}
          onClick={() => void manager.load(date, { symbol })}
        >
          CARREGAR
        </Button>
        <Button
          variant="outlined"
          disabled={!canPlay}
          onClick={() => manager.play()}
        >
          PLAY
        </Button>
        <Button
          variant="outlined"
          disabled={!canPause}
          onClick={() => manager.pause()}
        >
          PAUSE
        </Button>
        <Box>
          Replay: {state.clock == null ? '--:--:--' : new Date(state.clock).toLocaleTimeString('pt-BR')}
        </Box>
      </Stack>
      {state.error ? <Alert severity="error">{state.error}</Alert> : null}
      {state.controller ? (
        <Paper sx={{ p: { xs: 1, md: 2 }, minWidth: 0 }}>
          <Mt5CandlestickChart controller={state.controller} setup91Only />
        </Paper>
      ) : null}
      <Typography color="text.secondary">Simulacao 9.1: compra/venda, parcial 50%, BE protegido e saida GAIN / STOP / 0x0. BE protege o saldo; resultado inclui a parcial. Passe o cursor no candle para detalhes.</Typography>
      <Typography color="text.secondary">Status: {state.status}</Typography>
    </Stack>
  );
}
