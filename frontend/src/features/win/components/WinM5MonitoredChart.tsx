import { Alert, Button, Stack, TextField, Typography } from '@mui/material';
import { useState, useSyncExternalStore } from 'react';
import { Mt5CandlestickChart, type Mt5ChartController } from './Mt5CandlestickChart';
import { createSwingLevelMonitoring, validMonitoringConfig } from '../models/swingLevelMonitoring';
import type { AbsorptionOverlay } from '../../replay/models/absorptionReplay';

export function WinM5MonitoredChart({ controller, setup91Only = false, absorption, symbol }: {
  controller: Mt5ChartController; setup91Only?: boolean;
  absorption?: AbsorptionOverlay;
  symbol?: string;
}) {
  const [monitoring] = useState(() => {
    const candidate = {
      activation: Number(import.meta.env.VITE_WIN_SWING_MONITOR_ACTIVATION_POINTS ?? 100),
      deactivation: Number(import.meta.env.VITE_WIN_SWING_MONITOR_DEACTIVATION_POINTS ?? 150),
    };
    return createSwingLevelMonitoring(validMonitoringConfig(candidate) ? candidate : undefined);
  });
  const state = useSyncExternalStore(monitoring.subscribe, monitoring.getSnapshot);
  const [activation, setActivation] = useState(String(state.config.activation));
  const [deactivation, setDeactivation] = useState(String(state.config.deactivation));
  const config = { activation: Number(activation), deactivation: Number(deactivation) };
  const valid = activation.trim() !== '' && deactivation.trim() !== '' && validMonitoringConfig(config);
  const number = (value: number) => value.toLocaleString('pt-BR', { maximumFractionDigits: 2 });
  return <>
    <Mt5CandlestickChart controller={controller} setup91Only={setup91Only} monitoring={setup91Only || absorption ? undefined : monitoring} absorption={absorption} symbol={symbol} />
    {!setup91Only && !absorption ? <Stack spacing={1.5} sx={{ mt: 2 }}>
      <Typography variant="h6">Monitoramento de topos e fundos M5</Typography>
      <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap" alignItems="center">
        <TextField size="small" type="number" label="Ativar (pontos)" value={activation}
          onChange={event => setActivation(event.target.value)} slotProps={{ htmlInput: { min: 0 } }} />
        <TextField size="small" type="number" label="Desativar acima de (pontos)" value={deactivation}
          onChange={event => setDeactivation(event.target.value)} slotProps={{ htmlInput: { min: 0 } }} />
        <Button variant="outlined" disabled={!valid} onClick={() => monitoring.configure(config)}>Aplicar</Button>
        <Typography>LAST: {state.last == null ? '--' : number(state.last)}</Typography>
      </Stack>
      {!valid ? <Alert severity="warning">A distância de desativação deve superar a de ativação, que deve ser zero ou maior.</Alert> : null}
      <Typography variant="caption" color="text.secondary">
        Ativa em até {number(state.config.activation)} pontos; desativa acima de {number(state.config.deactivation)}.
        Níveis rompidos continuam acompanhados. Pivôs confirmados na sessão carregada.
      </Typography>
    </Stack> : null}
  </>;
}
