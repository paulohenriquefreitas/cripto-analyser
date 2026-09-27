import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { env } from '@/common/api/env';
import { createReplay } from '@/features/replay/api/replayApi';
import { connectReplay } from '@/features/replay/api/replayConnection';
import { createReplaySessionManager } from '@/features/replay/api/replaySessionManager';
import { Mt5CandlestickChart } from '@/features/win/components/Mt5CandlestickChart';

export function ReplayPage() {
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
          onClick={() => void manager.load(date)}
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
          <Mt5CandlestickChart controller={state.controller} />
        </Paper>
      ) : null}
      <Typography color="text.secondary">Status: {state.status}</Typography>
    </Stack>
  );
}
