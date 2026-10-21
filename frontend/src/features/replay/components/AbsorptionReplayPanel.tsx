import { Alert, Box, Button, MenuItem, Paper, Slider, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { WinM5MonitoredChart } from '../../win/components/WinM5MonitoredChart';
import type { ChartSink } from '../../win/models/mt5Intrabar';
import {
  absorptionMarkers, createAbsorptionOverlay, knownFinalState, locateAbsorption,
  parseAbsorptionReport, parseCandleBundle, visibleAbsorption, POSSIBLE, CONFIRMED,
  type AbsorptionReport, type CandleBundle,
} from '../models/absorptionReplay';

const utc = (ms: number | null) => ms == null ? 'sem timestamp' : new Date(ms).toISOString();
const filename = (source: string) => source.split(/[\\/]/).at(-1);
const emptyBundle: CandleBundle = { schema_version: 1, sessions: [] };

export function AbsorptionReplayPanel() {
  const [report, setReport] = useState<AbsorptionReport>();
  const [bundle, setBundle] = useState(emptyBundle);
  const [error, setError] = useState('');
  const [captureIndex, setCaptureIndex] = useState(0);
  const [cursor, setCursor] = useState(-1);
  const [selectedId, setSelectedId] = useState<string>();
  const [playing, setPlaying] = useState(false);
  const [overlay] = useState(createAbsorptionOverlay);
  useEffect(() => {
    const abort = new AbortController();
    void Promise.all(['replay.json', 'candles.json'].map(async file => {
      const response = await fetch(`${import.meta.env.BASE_URL}absorption/${file}`, { signal: abort.signal });
      if (!response.ok) throw new Error(`Não foi possível carregar ${file}: HTTP ${response.status}`);
      return response.json() as Promise<unknown>;
    })).then(([events, candles]) => {
      setReport(parseAbsorptionReport(events)); setBundle(parseCandleBundle(candles));
    }).catch(e => { if (!abort.signal.aborted) setError(String(e)); });
    return () => abort.abort();
  }, []);
  const capture = report?.captures[captureIndex];
  const session = bundle.sessions.find(s => s.capture_sha256 === capture?.source_sha256);
  const candles = useMemo(() => session?.candles ?? [], [session]);
  const controller = useMemo(() => ({ attachChart(sink: ChartSink) { sink.setHistory(candles); return () => {}; } }), [candles]);
  const markers = useMemo(() => capture ? absorptionMarkers(capture) : [], [capture]);
  const visible = useMemo(() => capture ? visibleAbsorption(capture, cursor) : [], [capture, cursor]);
  const selected = visible.find(m => m.id === selectedId) ?? visible.at(-1);
  const currentIndex = selected?.index ?? cursor;
  const current = capture?.transitions[cursor];
  const placements = useMemo(() => visible.map(m => locateAbsorption(m, session)), [visible, session]);
  useEffect(() => {
    overlay.update({ markers: placements.flatMap(p => p.placed ? [p.placed] : []),
      selectedId: selected?.id, focusId: selected?.id }, setSelectedId);
  }, [overlay, placements, selected?.id]);
  useEffect(() => {
    if (!playing) return;
    const timer = setTimeout(() => {
      const next = markers.find(m => m.index > cursor);
      if (!next) { setPlaying(false); return; }
      setSelectedId(next.id); setCursor(next.index);
    }, 500);
    return () => clearTimeout(timer);
  }, [playing, markers, cursor]);
  const go = (direction: number) => {
    const next = direction > 0 ? markers.find(m => m.index > currentIndex) : [...markers].reverse().find(m => m.index < currentIndex);
    if (next) { setCursor(next.index); setSelectedId(next.id); }
    setPlaying(false);
  };
  const inventory = useMemo(() => report?.captures.flatMap(c => absorptionMarkers(c)
    .filter(m => [POSSIBLE, CONFIRMED].includes(m.transition.state))
    .map(m => ({ capture: filename(c.source), event: m.transition.event_id, state: m.transition.state,
      utc: m.utcInterval, ...locateAbsorption(m, bundle.sessions.find(s => s.capture_sha256 === c.source_sha256)) }))) ?? [], [report, bundle]);
  const unplaced = inventory.filter(i => !i.placed);
  const possibleCount = inventory.filter(i => i.state === POSSIBLE).length;
  const reactionCount = inventory.filter(i => i.state === CONFIRMED).length;
  return <Stack spacing={2}>
    <Typography variant="h5">Replay de Absorção · WINZ26{session ? ` · M${session.timeframe_seconds/60}` : ''}</Typography>
    <Alert severity="info">Estudo experimental. Bolha amarela = possível absorção; seta verde/vermelha = reação da hipótese compradora/vendedora;
      × laranja = invalidada; losango cinza = dados insuficientes. Reação confirmada não representa operação lucrativa.</Alert>
    {error ? <Alert severity="error">{error}</Alert> : null}
    {!report && !error ? <Typography>Carregando os eventos reais do replay…</Typography> : null}
    {report ? <>
      <Typography>Inventário offline: {report.captures.length} capturas · {possibleCount} suspeitas · {reactionCount} reações confirmadas.
        Posicionáveis: {inventory.filter(i => i.placed && i.state === POSSIBLE).length} suspeitas e {inventory.filter(i => i.placed && i.state === CONFIRMED).length} reações.</Typography>
      <TextField select label="Captura" value={captureIndex} onChange={e => {
        setCaptureIndex(Number(e.target.value)); setCursor(-1); setSelectedId(undefined); setPlaying(false);
      }}>{report.captures.map((c, i) => <MenuItem key={c.source_sha256} value={i}>{filename(c.source)}</MenuItem>)}</TextField>
      <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
        <Button onClick={() => go(-1)} disabled={!markers.some(m => m.index < currentIndex)}>Estado anterior</Button>
        <Button onClick={() => go(1)} disabled={!markers.some(m => m.index > currentIndex)}>Próximo estado</Button>
        <Button disabled={!markers.length} onClick={() => setPlaying(p => !p)}>{playing ? 'Pausar' : 'Reproduzir estados'}</Button>
        <Button onClick={() => { setCursor(-1); setSelectedId(undefined); setPlaying(false); }}>Reiniciar</Button>
        <Button component="label" variant="outlined">Carregar candles compatíveis
          <input hidden type="file" accept="application/json,.json" onChange={async e => {
            const file = e.target.files?.[0];
            e.target.value = '';
            if (!file) return;
            try { setBundle(parseCandleBundle(JSON.parse(await file.text()))); setError(''); }
            catch (err) { setError(String(err)); }
          }} />
        </Button>
      </Stack>
      <Typography>Cursor de conhecimento: {utc(current?.known_at_local_ms ?? null)} · ordem observada {current?.observed_order ?? '—'}.
        Reprodução por estados (500 ms por passo), sem simular chegada individual de ticks.</Typography>
      <Slider aria-label="Cursor de conhecimento da absorção" min={-1} max={Math.max(0, (capture?.transitions.length ?? 1)-1)}
        step={1} value={cursor} onChange={(_, value) => { setCursor(value as number); setSelectedId(undefined); setPlaying(false); }} />
      {!session?.candles.length ? <Alert severity="warning">Candles históricos WINZ26 compatíveis indisponíveis para esta captura.
        Nenhum candle ou preço foi inventado e nenhum marcador foi forçado em outro contrato/horário.
        Eventos e evidências continuam disponíveis abaixo. {bundle.limitation}</Alert> : <Alert severity="info">
        Contexto histórico: {session.source}. Candles completos são retrospectivos; não simulam formação intrabar.
        Apenas estados conhecidos até o cursor são desenhados. Médias aparecem somente quando fornecidas nos candles.
      </Alert>}
      {session?.data_quality && (session.data_quality.missing_m1_utc.length > 0 || session.data_quality.mismatched_complete_minutes > 0) ?
        <Alert severity="warning">Qualidade do histórico: {session.data_quality.missing_m1_utc.length} minutos sem candle;
          {session.data_quality.mismatched_complete_minutes} minuto(s) com OHLC diferente dos LAST gravados na captura.
          O histórico original foi preservado; consulte a auditoria, sem interpretar captura como feed completo.</Alert> : null}
      <Paper sx={{ p: 2 }}><WinM5MonitoredChart controller={controller} setup91Only={false} absorption={overlay} /></Paper>
      {capture?.summary.integrity_faults.length ? <Typography>Auditoria final da captura (retrospectiva): {capture.summary.integrity_faults.join(', ')}.
        O detector bloqueou novas suspeitas após detectar falha.</Typography> : null}
      {visible.length ? <TextField select label="Selecionar marcador já conhecido" value={selected?.id ?? ''} onChange={e => setSelectedId(e.target.value)}>
        {visible.map(m => <MenuItem key={m.id} value={m.id}>#{m.transition.event_id} · {m.transition.state} · {utc(m.timeMs)}</MenuItem>)}
      </TextField> : <Typography>Nenhuma transição exibida neste ponto. Avance o cursor; capturas sem candidatos permanecem vazias.</Typography>}
      {selected && capture ? <Paper sx={{ p: 2 }}>
        <Typography variant="h6">Evento #{selected.transition.event_id} · {selected.transition.state}</Typography>
        <Typography>Hipótese {selected.transition.suspected_type} · preço {selected.price} · UTC {utc(selected.timeMs)}</Typography>
        <Typography>Faixa UTC disponível: {selected.utcInterval ? `[${utc(selected.utcInterval[0])}, ${utc(selected.utcInterval[1])})` : 'indisponível'}.
          Resolução: {capture.temporal_reference.utc_resolution_ms} ms; recebimento por lote.</Typography>
        <Typography>Agressão compradora {selected.transition.volume_buy} · vendedora {selected.transition.volume_sell} ·
          ambígua {selected.transition.volume_ambiguous} · delta {selected.transition.delta}</Typography>
        <Typography>Estado conhecido até o cursor: {knownFinalState(capture, selected.transition.event_id, cursor)}.
          O estado final só é revelado após sua transição.</Typography>
        <Typography>Motivo: {selected.transition.reason}</Typography>
        <Typography>time_msc original (auditoria, não usado para antecipar o marcador): {selected.transition.reference_market_time_msc}</Typography>
        {locateAbsorption(selected, session).reason ? <Alert severity="warning">{locateAbsorption(selected, session).reason}</Alert> : null}
        <Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 12 }}>
          {JSON.stringify({ dom: selected.transition.dom_evidence, qualidade: selected.transition.quality }, null, 2)}
        </Box>
      </Paper> : null}
      <details><summary>Eventos sem posição ({unplaced.length} transições das 17 suspeitas/5 reações)</summary>
        <Typography>Inventário retrospectivo; esta lista não antecipa marcadores no gráfico.</Typography>
        {unplaced.map((i, n) => <Typography key={n} variant="body2">{i.capture} · #{i.event} · {i.state} · {i.utc ? utc(i.utc[0]) : 'sem horário'}: {i.reason}</Typography>)}
      </details>
    </> : null}
  </Stack>;
}
