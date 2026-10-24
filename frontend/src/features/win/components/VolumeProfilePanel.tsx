import { Alert, Box, Chip, FormControlLabel, Paper, Stack, Switch, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { httpClient } from '@/common/api/httpClient';
import { profileBars, profileStatus, volumeProfileViewSchema } from '../models/volumeProfile';
import type { VolumeProfileView } from '../models/volumeProfile';

const number = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });
const format = (value: number | null) => value === null ? '—' : number.format(value);

export function VolumeProfilePanel({ symbol }: { symbol?: string }) {
  const [enabled, setEnabled] = useState(false);
  const [view, setView] = useState<VolumeProfileView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  useEffect(() => {
    if (!enabled) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function update() {
      try {
        const response = await httpClient.get<unknown>('/api/mt5/volume-profile', { signal: abort.signal, timeout: 5000 });
        const next = volumeProfileViewSchema.parse(response.data);
        if (next.profile && symbol && next.profile.symbol !== symbol) throw new Error('Contrato alterado');
        if (!abort.signal.aborted) { setView(next); setError(null); }
      } catch {
        if (!abort.signal.aborted) setError('Perfil indisponível. Os dados abaixo são o último estado recebido.');
      } finally {
        if (!abort.signal.aborted) timer = setTimeout(() => void update(), 1000);
      }
    }
    void update();
    return () => { abort.abort(); clearTimeout(timer); };
  }, [enabled, symbol]);
  const profile = view?.profile;
  const level = profile?.levels.find(row => row.price === selected);
  return <Paper sx={{ p: 2 }} data-testid="volume-profile-panel">
    <FormControlLabel label="Volume Profile + Delta por preço" control={
      <Switch checked={enabled} onChange={(_, checked) => setEnabled(checked)} />
    } />
    {enabled && <Stack spacing={1.5}>
      {error && <Alert severity="warning">{error}</Alert>}
      {!error && !view && <Typography>Carregando perfil…</Typography>}
      {view && !view.connected && <Alert severity="warning">Fluxo T&amp;T desconectado. Perfil preservado; continuidade não presumida.</Alert>}
      {view && !profile && <Typography>Nenhum negócio aceito para o perfil. Isso não representa volume zero do pregão.</Typography>}
      {profile && <>
        <Typography variant="h6">{profile.symbol} · {profile.session} · São Paulo</Typography>
        <Chip label={profileStatus(profile)} color={profile.integrityCompromised ? 'error' : profile.partial ? 'warning' : 'success'} sx={{ alignSelf: 'flex-start' }} />
        <Typography variant="body2">Volume observado {format(profile.totalVolume)} · Delta {format(profile.delta)} · {profile.trades} negócios</Typography>
        <Typography variant="body2">POC {format(profile.poc)} · VAH {format(profile.vah)} · VAL {format(profile.val)} · Área de valor {format(profile.valueAreaPercent)}%</Typography>
        <Typography variant="caption">Unidade: volume_real do MT5, sem conversão monetária. Verde: comprador · Vermelho: vendedor · Cinza: ambíguo.</Typography>
        {profile.partial && <Alert severity="info">Cobertura parcial: não representa necessariamente o pregão inteiro. {profile.coverageReasons.join(', ')}</Alert>}
        {profile.faults.length > 0 && <Alert severity="error">Falhas persistentes: {profile.faults.join(', ')}</Alert>}
        <Box role="group" aria-label="Volume executado por preço" sx={{ maxHeight: 420, overflowY: 'auto' }}>
          {profileBars(profile).map(row => <Box key={row.price} component="button" type="button"
            onClick={() => setSelected(row.price)} aria-pressed={row.price === selected}
            aria-label={`${row.price}: volume ${row.totalVolume}, delta ${row.delta}${row.poc ? ', POC' : ''}${row.vah ? ', VAH' : ''}${row.val ? ', VAL' : ''}`}
            sx={{ display: 'flex', alignItems: 'center', width: '100%', gap: 1, p: .5,
              bgcolor: row.price === selected ? 'action.selected' : 'transparent', color: 'text.primary', cursor: 'pointer',
              border: row.poc ? '2px solid' : '1px solid transparent', borderColor: row.poc ? 'warning.main' : 'transparent',
              borderTop: row.vah ? '1px dashed' : undefined, borderBottom: row.val ? '1px dashed' : undefined }}>
            <Box sx={{ width: 140, textAlign: 'left', flexShrink: 0 }}>{format(row.price)} {row.poc ? 'POC' : ''} {row.vah ? 'VAH' : ''} {row.val ? 'VAL' : ''}</Box>
            <Box sx={{ display: 'flex', flex: 1, minWidth: 40, height: 18 }}>
              <Box sx={{ width: `${row.buyWidth}%`, bgcolor: 'success.main' }} />
              <Box sx={{ width: `${row.sellWidth}%`, bgcolor: 'error.main' }} />
              <Box sx={{ width: `${row.ambiguousWidth}%`, bgcolor: 'grey.500' }} />
            </Box>
            <Box sx={{ width: 75, textAlign: 'right' }}>{format(row.totalVolume)}</Box>
          </Box>)}
        </Box>
        {level ? <Typography role="status">Preço {format(level.price)} · Delta {format(level.delta)} · Compra {format(level.buyVolume)} ({format(level.buyPercent)}%) · Venda {format(level.sellVolume)} ({format(level.sellPercent)}%) · Ambíguo {format(level.ambiguousVolume)} ({format(level.ambiguousPercent)}%) · {level.trades} negócios</Typography>
          : <Typography variant="caption">Selecione um preço para consultar volume, delta e participação de cada lado.</Typography>}
        <Typography variant="caption">HVN: {profile.hvn.map(r => `${format(r.low)}–${format(r.high)}`).join(', ') || '—'} · LVN: {profile.lvn.map(r => `${format(r.low)}–${format(r.high)}`).join(', ') || '—'}</Typography>
      </>}
    </Stack>}
  </Paper>;
}
