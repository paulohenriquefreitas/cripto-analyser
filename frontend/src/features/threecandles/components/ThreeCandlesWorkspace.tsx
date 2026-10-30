import {
  Alert,
  Button,
  Checkbox,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState, type ReactNode } from 'react';
import type { ChartSink } from '@/features/win/models/mt5Intrabar';
import type { CandleView } from '../api/threeCandlesApi';
import {
  analyzeFibonacci,
  fibVersion,
  observeFibonacci,
  type FibAnchor,
  type FibVersion,
} from '../models/FibonacciMovementEngine';
import { ThreeCandlesChart } from './ThreeCandlesChart';
import { type FibonacciOverlay } from './FibonacciPrimitive';
import { DowDebugPanel } from './DowDebugPanel';

const price = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 2 });
const reasons: Record<string, string> = {
  CONFIRMED_STRUCTURAL_REVERSAL_UP: 'Novo movimento de alta por topo superior confirmado',
  CONFIRMED_STRUCTURAL_REVERSAL_DOWN: 'Novo movimento de baixa por fundo inferior confirmado',
  INITIAL_OPPOSITE_PAIR_HYPOTHESIS: 'Primeira dupla oposta confirmada (hipótese)',
  CONFIRMED_HIGHER_TOP_HYPOTHESIS: 'Ampliação por topo mais alto confirmado (hipótese)',
  CONFIRMED_LOWER_BOTTOM_HYPOTHESIS: 'Ampliação por fundo mais baixo confirmado (hipótese)',
  USER_SELECTED_ANCHORS: 'Âncoras escolhidas pelo usuário',
};
export type WorkspacePresentation = {
  fibonacci: boolean;
  dow: boolean;
  dowFifty: boolean;
  dowLabels: boolean;
  micro: boolean;
  microFifty?: boolean;
  microLabels: boolean;
  allMicroFractals: boolean;
  opening?: boolean;
};
export function ThreeCandlesWorkspace({
  controller,
  view,
  candleDisplayTimeZone,
  structuralLevels = true,
  presentation,
  layerControls,
  supportControls,
  supportDiagnostics,
  dataDiagnostics,
  sessionSummary,
}: {
  controller: { attachChart: (sink: ChartSink) => () => void };
  view?: CandleView;
  candleDisplayTimeZone: string;
  presentation?: { current: WorkspacePresentation };
  layerControls?: ReactNode;
  supportControls?: ReactNode;
  supportDiagnostics?: ReactNode;
  dataDiagnostics?: ReactNode;
  sessionSummary?: ReactNode;
  candlesOnly?: boolean;
  structuralLevels?: boolean;
  visibleCandleViewport?: boolean;
}) {
  const [visible, setVisible] = useState(presentation?.current.fibonacci ?? false),
    [compare, setCompare] = useState(false);
  const [mode, setMode] = useState<'MANUAL' | 'EXPERIMENTAL'>('EXPERIMENTAL');
  const [scope, setScope] = useState<5 | 60>(5),
    [tolerance, setTolerance] = useState(5);
  const [highId, setHighId] = useState(''),
    [lowId, setLowId] = useState('');
  const [pick, setPick] = useState<'TOP' | 'BOTTOM'>(),
    [manual, setManual] = useState<FibVersion[]>([]);
  const [error, setError] = useState('');
  const [showDow, setShowDow] = useState(presentation?.current.dow ?? true),
    [dowLabels, setDowLabels] = useState(presentation?.current.dowLabels ?? false),
    [selectedDow, setSelectedDow] = useState<string>();
  const [showMicro, setShowMicro] = useState(presentation?.current.micro ?? true),
    [microLabels, setMicroLabels] = useState(presentation?.current.microLabels ?? false);
  const [allMicroFractals, setAllMicroFractals] = useState(
    presentation?.current.allMicroFractals ?? false,
  );
  const [showOpening, setShowOpening] = useState(presentation?.current.opening ?? true);
  const [openingMinimum, setOpeningMinimum] = useState(0);
  const supported = structuralLevels && view?.timeframeMinutes !== 15;
  const remember = (key: keyof WorkspacePresentation, value: boolean) => {
    if (presentation) presentation.current[key] = value;
  };
  const microDowOverlay = useMemo(
    () => ({
      snapshot: view?.microDow,
      visible: showMicro && supported,
      labels: microLabels,
      allFractals: allMicroFractals,
    }),
    [view?.microDow, showMicro, microLabels, allMicroFractals, supported],
  );
  const dowOverlay = useMemo(
    () => ({ snapshot: view?.dow, visible: showDow && supported, labels: dowLabels }),
    [view?.dow, showDow, dowLabels, supported],
  );
  const [showDowFifty, setShowDowFifty] = useState(presentation?.current.dowFifty ?? true),
    [selectedFifty, setSelectedFifty] = useState<string>();
  const [showMicroFifty, setShowMicroFifty] = useState(presentation?.current.microFifty ?? true);
  const dowFiftyOverlay = useMemo(
    () => ({ snapshot: view?.dowFifty, visible: showDowFifty && supported }),
    [view?.dowFifty, showDowFifty, supported],
  );
  const fifty = view?.dowFifty;
  const formingFifty = fifty?.formingWave;
  const currentFifty = fifty?.references.find((r) => r.wave.id === fifty.currentWaveId);
  const microFifty = view?.microDowFifty;
  const currentMicroFifty = microFifty?.references.find(r=>r.wave.id===microFifty.currentWaveId);
  const principalLevel = showDowFifty && supported
    ? formingFifty?.level50 ?? (currentFifty?.endedAtMsc==null ? currentFifty?.wave.level50 : undefined) : undefined;
  const inspectedFifty =
    fifty?.references.find((r) => r.wave.id === (selectedFifty ?? fifty.currentWaveId)) ??
    currentFifty;
  const fiftyState: Record<string, string> = {
    AGUARDANDO_ONDA: 'Aguardando onda',
    ONDA_CONFIRMADA: 'Onda confirmada',
    MONITORANDO_50: 'Monitorando 50%',
    ROMPIMENTO_EM_AVALIACAO: 'Rompimento em avaliacao',
    REJEICAO_EM_AVALIACAO: 'Rejeicao em avaliacao',
    ONDA_ENCERRADA: 'Onda encerrada',
  };
  const analysis = useMemo(() => analyzeFibonacci(view, tolerance), [view, tolerance]);
  const lane = analysis.lanes.find((l) => l.timeframeMinutes === scope)!;
  const automatic = lane.versions.at(-1);
  const manualKnown = manual.filter((v) => v.decidedAtMsc <= analysis.cutoff);
  const manualCurrent = manualKnown
    .filter((v) => v.timeframeMinutes === scope)
    .slice()
    .sort((a, b) => a.decidedAtMsc - b.decidedAtMsc)
    .at(-1);
  const anchors = analysis.anchors.filter((a) => a.timeframeMinutes === scope);
  const primary = mode === 'MANUAL' ? manualCurrent : automatic;
  const comparison = compare ? (mode === 'MANUAL' ? automatic : manualCurrent) : undefined;
  const overlay: FibonacciOverlay = useMemo(
    () => ({
      primary: visible ? primary : undefined,
      comparison: visible ? comparison : undefined,
      anchors,
      pick,
    }),
    [primary, comparison, anchors, pick, visible],
  );
  const time = (t: number) =>
    new Intl.DateTimeFormat('pt-BR', {
      timeZone: candleDisplayTimeZone,
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(t));
  const selectAnchor = (a: FibAnchor) => {
    if (pick === 'TOP') setHighId(a.id);
    else if (pick === 'BOTTOM') setLowId(a.id);
    setPick(undefined);
    setError('');
  };
  const applyManual = () => {
    try {
      const high = anchors.find((a) => a.id === highId),
        low = anchors.find((a) => a.id === lowId);
      if (!high || !low)
        throw new Error('Escolha um topo e um fundo já confirmados da mesma amplitude.');
      const version = fibVersion(
        high,
        low,
        analysis.cutoff,
        'MANUAL',
        'USER_SELECTED_ANCHORS',
        manualCurrent,
        manual.length + 1,
      );
      setManual((previous) => [...previous, version]);
      setMode('MANUAL');
      setError('');
      setPick(undefined);
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e));
    }
  };
  const audit = [...lane.versions, ...manualKnown.filter((v) => v.timeframeMinutes === scope)].sort(
    (a, b) => a.decidedAtMsc - b.decidedAtMsc,
  );
  const manualObservations = observeFibonacci(
    manualKnown
      .filter((v) => v.timeframeMinutes === scope)
      .slice()
      .sort((a, b) => a.decidedAtMsc - b.decidedAtMsc),
    view?.candles.filter((c) => c.availableAtMsc <= analysis.cutoff) ?? [],
    tolerance,
  );
  const observations = [...lane.observations, ...manualObservations].sort(
    (a, b) => a.atMsc - b.atMsc,
  );
  const dowProps = {
    snapshot: view?.dow,
    visible: showDow,
    labels: dowLabels,
    onVisible: (v: boolean) => {
      setShowDow(v);
      remember('dow', v);
    },
    onLabels: (v: boolean) => {
      setDowLabels(v);
      remember('dowLabels', v);
    },
    selectedId: selectedDow,
    onSelect: setSelectedDow,
    zone: candleDisplayTimeZone,
    disabled: !supported,
  };
  return (
    <Stack
      spacing={1.5}
      sx={{
        minWidth: 0,
        '& details': {
          minWidth: 0,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 1,
          p: 1.5,
        },
        '& summary': { cursor: 'pointer', fontWeight: 600, p: 0.5 },
        '& summary:focus-visible': {
          outline: '2px solid',
          outlineColor: 'primary.main',
          outlineOffset: 2,
        },
        '& details > :not(summary)': { mt: 1 },
        '& details .MuiFormControl-root': { minWidth: 0, maxWidth: '100%', width: 260 },
        '& details .MuiFormControlLabel-root': { maxWidth: '100%' },
        '& details table': {
          display: 'block',
          maxWidth: '100%',
          overflowX: 'auto',
          borderCollapse: 'collapse',
          fontSize: 13,
        },
        '& details th, & details td': {
          textAlign: 'left',
          p: 1,
          verticalAlign: 'top',
          borderBottom: '1px solid',
          borderColor: 'divider',
        },
        '& pre': {
          maxWidth: '100%',
          overflowX: 'auto',
          whiteSpace: 'pre-wrap',
          overflowWrap: 'anywhere',
        },
        '& .MuiAlert-message': { minWidth: 0, overflowWrap: 'anywhere' },
      }}
    >
      <Stack
        direction="row"
        useFlexGap
        flexWrap="wrap"
        gap={1}
        alignItems="center"
        role="group"
        aria-label="Camadas do gráfico"
        data-testid="chart-layers"
      >
        <FormControlLabel label="Dow principal" control={<Checkbox disabled={!supported} checked={showDow}
          onChange={(_,v)=>{setShowDow(v);remember('dow',v);}} />} />
        <FormControlLabel label="Dow micro" control={<Checkbox disabled={!supported} checked={showMicro}
          onChange={(_,v)=>{setShowMicro(v);remember('micro',v);}} />} />
        <FormControlLabel
          label="50% Dow principal"
          control={
            <Checkbox
              disabled={!supported}
              checked={showDowFifty}
              onChange={(_, v) => {
                setShowDowFifty(v);
                remember('dowFifty', v);
              }}
            />
          }
        />
        <FormControlLabel label="50% Dow micro" control={<Checkbox disabled={!supported} checked={showMicroFifty}
          onChange={(_,v)=>{setShowMicroFifty(v);remember('microFifty',v);}} />} />
        <FormControlLabel label="Abertura experimental" control={<Checkbox disabled={!supported} checked={showOpening}
          onChange={(_,v)=>{setShowOpening(v);remember('opening',v);}} />} />
        {layerControls}
      </Stack>
      {pick && (
        <Alert severity="info">
          Seleção manual armada: clique em um {pick === 'TOP' ? 'topo' : 'fundo'} confirmado.{' '}
          <Button size="small" onClick={() => setPick(undefined)}>
            Cancelar captura
          </Button>
        </Alert>
      )}
      <ThreeCandlesChart
        controller={controller}
        zone={candleDisplayTimeZone}
        overlay={overlay}
        onPick={selectAnchor}
        dowOverlay={dowOverlay}
        microDowOverlay={microDowOverlay}
        openingOverlay={{snapshot:view?.openingStructure,visible:showOpening && supported,minimumPoints:openingMinimum}}
        dowFiftyOverlay={dowFiftyOverlay}
        microFiftyOverlay={{snapshot:microFifty,visible:showMicroFifty && supported,principalLevel}}
        onPickDowFifty={setSelectedFifty}
        onPickDow={(p) => setSelectedDow(p.extreme.id)}
      />
      {supported && showOpening && view?.openingStructure && (
        <Stack spacing={0.5} data-testid="opening-structure-panel">
          <Typography variant="body2">
            Abertura experimental: {view.openingStructure.initialLeg
              ? `${view.openingStructure.initialLeg.direction === 'DOWN' ? 'baixa' : 'alta'} EM FORMAÇÃO · ${price(view.openingStructure.initialLeg.amplitudePoints)} pontos · conhecido em ${time(view.openingStructure.initialLeg.availableAtMsc)}`
              : view.openingStructure.state === 'UNAVAILABLE' ? 'M5 das 09:00 ainda indisponível' : 'aguardando direção'}
          </Typography>
          <Typography variant="caption">{view.openingStructure.gap
            ? `GAP: ${price(view.openingStructure.gap.points)} pontos entre último fechamento M5 observado e abertura; não representa negócios fora da sessão.`
            : 'GAP indisponível: falta abertura ou referência de D-1.'}</Typography>
          <details><summary>Contexto de abertura e amplitudes</summary>
            <Typography variant="body2">{view.openingStructure.previousSession
              ? `Última sessão disponível: ${view.openingStructure.previousSession.date}. Fechamento M5 observado ${price(view.openingStructure.previousSession.close.price)}; máxima observada ${price(view.openingStructure.previousSession.high.price)}; mínima observada ${price(view.openingStructure.previousSession.low.price)}. Cobertura M5 não certifica fechamento final.`
              : 'D-1 indisponível para este contrato.'} Ajuste oficial indisponível: nenhuma fonte oficial integrada.</Typography>
            <Typography variant="body2">Abertura negociada: {view.openingStructure.tradedOpen ? `${price(view.openingStructure.tradedOpen.price)} (disponível em ${time(view.openingStructure.tradedOpen.availableAtMsc)})` : 'indisponível'}.
              Extremos desde 09h: {view.openingStructure.observedHigh ? price(view.openingStructure.observedHigh.price) : '—'} / {view.openingStructure.observedLow ? price(view.openingStructure.observedLow.price) : '—'}.
              {view.openingStructure.coverage === 'CONTIGUOUS_CLOSED_M5' ? ' Sequência M5 completa desde 09h.' : ' Cobertura M5 incompleta.'}</Typography>
            {view.openingStructure.windows.map(w=><Typography key={w.minutes} variant="body2">09:00–{w.minutes===15?'09:15':w.minutes===30?'09:30':'09:45'}: {w.amplitudePoints==null?'—':price(w.amplitudePoints)} pontos · {w.status === 'COMPLETE' ? 'completa' : w.status === 'INCOMPLETE' ? 'dados incompletos' : 'em formação'}{w.availableAtMsc==null?'':` · conhecido em ${time(w.availableAtMsc)}`}</Typography>)}
            <TextField select size="small" label="Amplitude mínima experimental" value={openingMinimum} onChange={e=>setOpeningMinimum(Number(e.target.value))}>
              {[0,250,300].map(n=><MenuItem key={n} value={n}>{n} pontos</MenuItem>)}
            </TextField>
            <Typography variant="body2">Comparação da perna: {view.openingStructure.thresholds.map(t=>`${t.minimumPoints} pontos: ${t.firstReachedAtMsc==null?'não atingido':time(t.firstReachedAtMsc)}`).join(' · ')}</Typography>
            <details><summary>Versões por M5 fechado ({view.openingStructure.legHistory.length})</summary>
              {view.openingStructure.legHistory.map(l=><Typography key={l.availableAtMsc} variant="body2">Disponível {time(l.availableAtMsc)} · origem {time(l.origin.formationTimeMsc)} a {price(l.origin.price)} · destino {time(l.destination.formationTimeMsc)} a {price(l.destination.price)} · {price(l.amplitudePoints)} pontos · {l.intrabarOrder==='UNKNOWN_SAME_M5'?'ordem intrabar desconhecida':'candles distintos'}</Typography>)}
            </details>
            <Typography variant="caption">Filtro apenas da linha de abertura. Pivôs 1+1 e 2+2 permanecem independentes. A ordem dos extremos dentro do mesmo M5 é desconhecida; esse trecho não é conectado. Nenhum 50% de abertura está ativo.</Typography>
          </details>
        </Stack>
      )}
      <Stack spacing={0.5} aria-label="Resumo da estrutura" data-testid="structure-summary">
        <DowDebugPanel {...dowProps} part="summary" />
        <Typography variant="body2" data-testid="micro-fifty-current">
          {!supported ? 'DOW MICRO 50%: indisponível em M15' : currentMicroFifty
            ? `DOW MICRO 50% — ${price(currentMicroFifty.wave.level50)} / confirmada em ${time(currentMicroFifty.wave.availableAtMsc)}`
            : 'DOW MICRO 50%: aguardando onda entre pivôs micro confirmados'}
        </Typography>
        <Stack spacing={0.5} data-testid="dow-fifty-panel">
          <Typography variant="body2" data-testid="dow-fifty-current">
            {!supported
              ? 'DOW 50%: indisponível em M15'
              : formingFifty
                ? `DOW 50% \u2014 ${price(formingFifty.level50)} / em formacao / rompimento observado em ${time(formingFifty.recognizedAtMsc)}`
                : currentFifty
                ? `DOW 50% \u2014 ${price(currentFifty.wave.level50)} / ${fiftyState[currentFifty.state]} / confirmada em ${time(currentFifty.wave.recognizedAtMsc)}`
                : `DOW 50%: ${fiftyState.AGUARDANDO_ONDA}`}
          </Typography>
          {supported && showDowFifty && (
            <>
              {formingFifty && (
                <Typography variant="caption">
                  Onda em formacao: fundo {price(formingFifty.low)} / topo {price(formingFifty.high)}.
                  O extremo acompanha os candles observados ate a confirmacao do pivo principal.
                </Typography>
              )}
              {!formingFifty && currentFifty && (
                <Typography variant="caption">
                  Lado observado: {currentFifty.approachSide} / Rompimento:{' '}
                  {currentFifty.breakStatus} / Retorno: {currentFifty.rejectionStatus}. Referencias
                  possiveis: topo {price(currentFifty.wave.high)} e fundo{' '}
                  {price(currentFifty.wave.low)}.
                </Typography>
              )}{' '}
            </>
          )}
        </Stack>
        {sessionSummary}
        <Typography variant="caption" color="text.secondary">
          Reação aos 50% experimental; hipóteses de estudo, sem ordens ou destinos garantidos.
        </Typography>
      </Stack>
      {supported && showDow && !view?.dow && view && (
        <Alert severity="warning">Estrutura Dow M5 indisponível nesta resposta do backend.</Alert>
      )}
      {supported && showDowFifty && !fifty && view && (
        <Alert severity="warning">
          50% Dow indisponível nesta resposta do backend. Nenhuma onda será inferida no navegador.
        </Alert>
      )}
      {supported && showMicro && !view?.microDow && view && (
        <Alert severity="info">Ondas micro indisponíveis nesta resposta do backend.</Alert>
      )}
      {supported && visible && !analysis.fullMemory && view?.structure && (
        <Alert severity="warning">
          Fibonacci ativa com memória de pivôs limitada nesta resposta. Confira os detalhes em
          Configurações avançadas.
        </Alert>
      )}
      <DowDebugPanel {...dowProps} part="selection" />
      {selectedDow && (
        <Button
          size="small"
          onClick={() => setSelectedDow(undefined)}
          sx={{ alignSelf: 'flex-start' }}
        >
          Fechar detalhe do pivô
        </Button>
      )}
      {selectedFifty && inspectedFifty && (
        <Alert severity="info" data-testid="dow-fifty-selected">
          Onda {inspectedFifty.wave.id} / {inspectedFifty.wave.direction} /{' '}
          {fiftyState[inspectedFifty.state]}.<br />
          Inicial: {inspectedFifty.wave.initial.id}, {price(inspectedFifty.wave.initial.price)},
          formacao {time(inspectedFifty.wave.initial.extremeTimeMsc)}, confirmacao{' '}
          {time(inspectedFifty.wave.initial.confirmedAtMsc)}.<br />
          Final: {inspectedFifty.wave.terminal.id}, {price(inspectedFifty.wave.terminal.price)},
          formacao {time(inspectedFifty.wave.terminal.extremeTimeMsc)}, confirmacao{' '}
          {time(inspectedFifty.wave.terminal.confirmedAtMsc)}.<br />
          Maxima {price(inspectedFifty.wave.high)}, minima {price(inspectedFifty.wave.low)}, 50%{' '}
          {price(inspectedFifty.wave.level50)}. Conclusao reconhecida / linha vigente desde{' '}
          {time(inspectedFifty.wave.recognizedAtMsc)} / evento {inspectedFifty.wave.sourceEventId}.
          <br />
          Referências possíveis: topo {price(inspectedFifty.wave.high)} e fundo{' '}
          {price(inspectedFifty.wave.low)}.<br />
          Ultima reacao:{' '}
          {fifty?.events
            .filter(
              (e) =>
                e.waveId === inspectedFifty.wave.id &&
                /TOUCH|BREAK|REJECTION|DESTINATION/.test(e.kind),
            )
            .at(-1)?.kind ?? 'Nenhuma'}
          . Consultar historico nao reativa referencias encerradas.
        </Alert>
      )}
      {selectedFifty && (
        <Button
          size="small"
          onClick={() => setSelectedFifty(undefined)}
          sx={{ alignSelf: 'flex-start' }}
        >
          Fechar detalhes 50%
        </Button>
      )}
      <details data-testid="advanced-settings">
        <summary>Configurações avançadas</summary>
        <Stack spacing={1.5}>
          <details>
            <summary>Estrutura de Dow</summary>
            <DowDebugPanel {...dowProps} part="labels" />
            <Stack direction="row" useFlexGap flexWrap="wrap">
              <FormControlLabel
                label="Mostrar todos os fractais micro confirmados"
                control={
                  <Checkbox
                    disabled={!supported}
                    checked={allMicroFractals}
                    onChange={(_, v) => {
                      setAllMicroFractals(v);
                      remember('allMicroFractals', v);
                    }}
                  />
                }
              />
              <FormControlLabel
                label="Mostrar etiquetas HH/HL/LH/LL micro"
                control={
                  <Checkbox
                    disabled={!supported}
                    checked={microLabels}
                    onChange={(_, v) => {
                      setMicroLabels(v);
                      remember('microLabels', v);
                    }}
                  />
                }
              />
            </Stack>
            {showMicro && !view?.microDow && (
              <Alert severity="info">Ondas micro indisponiveis nesta resposta do backend.</Alert>
            )}
            {showMicro && (
              <Typography variant="caption">
                Fractais: {view?.microDow?.pivots.length ?? 0} / Utilizados:{' '}
                {view?.microDow?.selectedPivotIds?.length ??
                  new Set(view?.microDow?.movements.flatMap((m) => [m.fromId, m.toId]) ?? [])
                    .size}{' '}
                / Segmentos: {view?.microDow?.movements.length ?? 0} / Versoes historicas:{' '}
                {view?.microDow?.movementHistory?.length ?? view?.microDow?.movements.length ?? 0}
              </Typography>
            )}
            {showMicro && view?.microDow?.opening && (
              <Typography variant="caption">
                Abertura micro: {view.microDow.opening.status} / referencia ambar separada dos pivos
                confirmados.
              </Typography>
            )}

            <DowDebugPanel {...dowProps} part="help" />
          </details>
          <details>
            <summary>Suportes e resistências</summary>
            {supportControls}
          </details>
          <details>
            <summary>Fibonacci antiga / Comparação manual</summary>
            <Stack spacing={1}>
              <Stack direction="row" gap={2} flexWrap="wrap" alignItems="center">
                <FormControlLabel
                  label="Fibonacci"
                  control={
                    <Checkbox
                      checked={visible}
                      onChange={(_, v) => {
                        setVisible(v);
                        remember('fibonacci', v);
                      }}
                    />
                  }
                />
                <TextField
                  size="small"
                  select
                  label="Modo Fibonacci"
                  value={mode}
                  onChange={(e) => setMode(e.target.value as typeof mode)}
                  sx={{ minWidth: 230 }}
                >
                  <MenuItem value="EXPERIMENTAL">Automático experimental</MenuItem>
                  <MenuItem value="MANUAL">Manual</MenuItem>
                </TextField>
                <TextField
                  size="small"
                  select
                  label="Amplitude Fibonacci"
                  value={scope}
                  onChange={(e) => {
                    setScope(Number(e.target.value) as 5 | 60);
                    setHighId('');
                    setLowId('');
                    setPick(undefined);
                  }}
                  sx={{ minWidth: 190 }}
                >
                  <MenuItem value={5}>Micro M5</MenuItem>
                  <MenuItem value={60}>Macro H1 2+2</MenuItem>
                </TextField>
                <FormControlLabel
                  label="Comparar manual e automático"
                  control={<Checkbox checked={compare} onChange={(_, v) => setCompare(v)} />}
                />
                <TextField
                  size="small"
                  select
                  label="Aproximação 50% (pontos)"
                  value={tolerance}
                  onChange={(e) => setTolerance(Number(e.target.value))}
                  sx={{ minWidth: 220 }}
                >
                  {[0, 5, 15, 30].map((n) => (
                    <MenuItem key={n} value={n}>
                      {n}
                    </MenuItem>
                  ))}
                </TextField>
              </Stack>
              <Typography variant="caption">
                Estudo experimental, sem sinal operacional. Extremos confirmados ampliam o
                movimento; um extremo contrário que supera a amplitude inicia outro movimento a
                partir do extremo terminal anterior. Toques em 50% não trocam âncoras. Extremos
                internos ou iguais não definem reversão.
              </Typography>
              {visible && !analysis.fullMemory && view?.structure && (
                <Alert severity="info">
                  Memória completa indisponível nesta resposta: usando os pivôs M5 e candidatos H1
                  originais disponíveis. H1 intradiário requer o backend atualizado.
                </Alert>
              )}
              <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
                <Button
                  onClick={() => setPick('TOP')}
                  disabled={!anchors.some((a) => a.type === 'TOP')}
                >
                  Selecionar máxima no gráfico
                </Button>
                <Button
                  onClick={() => setPick('BOTTOM')}
                  disabled={!anchors.some((a) => a.type === 'BOTTOM')}
                >
                  Selecionar mínima no gráfico
                </Button>
                <Button onClick={() => setPick(undefined)} disabled={!pick}>
                  Cancelar seleção
                </Button>
                <TextField
                  size="small"
                  select
                  label="Máxima manual confirmada"
                  value={anchors.some((a) => a.id === highId) ? highId : ''}
                  onChange={(e) => setHighId(e.target.value)}
                  sx={{ minWidth: 235 }}
                >
                  <MenuItem value="">Selecione um topo</MenuItem>
                  {anchors
                    .filter((a) => a.type === 'TOP')
                    .map((a) => (
                      <MenuItem key={a.id} value={a.id}>
                        {price(a.price)} · {time(a.extremeTimeMsc)}
                      </MenuItem>
                    ))}
                </TextField>
                <TextField
                  size="small"
                  select
                  label="Mínima manual confirmada"
                  value={anchors.some((a) => a.id === lowId) ? lowId : ''}
                  onChange={(e) => setLowId(e.target.value)}
                  sx={{ minWidth: 235 }}
                >
                  <MenuItem value="">Selecione um fundo</MenuItem>
                  {anchors
                    .filter((a) => a.type === 'BOTTOM')
                    .map((a) => (
                      <MenuItem key={a.id} value={a.id}>
                        {price(a.price)} · {time(a.extremeTimeMsc)}
                      </MenuItem>
                    ))}
                </TextField>
                <Button onClick={applyManual} disabled={!highId || !lowId}>
                  Aplicar Fibonacci manual
                </Button>
              </Stack>
              {pick && (
                <Alert severity="info">
                  Clique no círculo azul de um {pick === 'TOP' ? 'topo' : 'fundo'} confirmado. Use a
                  navegação para localizar a origem; os seletores também permitem escolher âncoras
                  fora do viewport.
                </Alert>
              )}
              {error && <Alert severity="warning">{error}</Alert>}
              <Typography data-testid="fibonacci-current" variant="body2">
                {primary
                  ? `FIBO 50% — ${price(primary.fib50)} · ${mode === 'MANUAL' ? 'Manual' : 'Hipótese automática'} · ${scope === 5 ? 'Micro M5' : 'Macro H1'} · ${primary.direction === 'UP' ? 'Alta' : 'Baixa'} · máxima ${price(primary.high.price)} · mínima ${price(primary.low.price)} · decisão ${time(primary.decidedAtMsc)}`
                  : mode === 'MANUAL'
                    ? 'Fibonacci manual aguardando duas âncoras confirmadas.'
                    : 'Aguardando dupla oposta de pivôs confirmados para a hipótese automática.'}
              </Typography>
              <Typography variant="caption">
                Linha principal: branca no tema escuro e escura no tema claro, contínua 2,5 px;
                comparação: azul pontilhada. 0% na máxima e 100% na mínima; A e B seguem a ordem dos
                extremos no tempo. Diagonal pontilhada e faixa azul delimitam a amplitude. Nenhum
                nível amplia o autoscale. Seleções manuais são independentes e, ao reiniciar,
                reaparecem quando o Replay alcança o instante em que foram feitas.
              </Typography>
              <Typography variant="caption">
                A tolerância desta Fibonacci é local e não altera a política do monitor DOW 50% no
                backend. Micro M5 usa pivôs 2+2, não Dow micro 1+1.
              </Typography>
            </Stack>
          </details>
          <details>
            <summary>Ajuda e informações técnicas</summary>
            <Typography variant="caption">
              Fonte exclusiva: movimentos do Dow principal 2+2 disponiveis na confirmacao do pivo terminal.
              O nivel acompanha a onda atual e suas extensoes confirmadas. Antes da confirmacao, um rompimento do extremo oposto mostra a onda em formacao com os precos ja observados. Fibonacci antiga e manual sao
              independentes; a modalidade antiga continua disponivel acima para comparacao.
            </Typography>
            <Typography variant="caption">
              Hipotese de rompimento: fechamento M5 alem dos 50% (experimental). Hipotese de
              retorno: toque e fechamento no mesmo lado (candidata nao validada). Tolerancia
              propria: {fifty?.approachTolerancePoints ?? 5} pontos. Sem ordens ou destinos
              garantidos.
            </Typography>{' '}
          </details>
        </Stack>
      </details>
      <details data-testid="audit-diagnostics">
        <summary>Auditoria e diagnósticos</summary>
        <Stack spacing={1.5}>
          <details>
            <summary>Dow principal</summary>
            <DowDebugPanel {...dowProps} part="audit" />
          </details>
          <details>
            <summary>Dow micro</summary>
            {
              <details>
                <summary>
                  Auditoria micro: {view?.microDow?.pivots.length ?? 0} pivos confirmados
                </summary>
                <pre>{JSON.stringify(view?.microDow, null, 2)}</pre>
              </details>
            }{' '}
          </details>
          <details>
            <summary>DOW MICRO 50% — referências e substituições</summary>
            <Typography variant="body2">Somente ondas locais selecionadas pelo micro, com dois pivôs confirmados. A referência de abertura não participa. Consultar o histórico não reativa níveis antigos.</Typography>
            <table>
              <thead><tr><th>Disponível</th><th>Onda / pivôs</th><th>Origem: preço / formação / confirmação</th><th>Destino: preço / formação / confirmação</th><th>50%</th><th>Estado / substituição</th></tr></thead>
              <tbody>{microFifty?.references.map(r=><tr key={r.wave.id}>
                <td>{time(r.wave.availableAtMsc)}</td>
                <td>{r.wave.id}<br/>{r.wave.fromId}<br/>{r.wave.toId}</td>
                <td>{price(r.wave.initial.price)} / {time(r.wave.initial.extremeTimeMsc)} / {time(r.wave.initial.confirmedAtMsc)}</td>
                <td>{price(r.wave.terminal.price)} / {time(r.wave.terminal.extremeTimeMsc)} / {time(r.wave.terminal.confirmedAtMsc)}</td>
                <td>{price(r.wave.level50)}</td>
                <td>Confirmada · {r.supersededAtMsc==null?'vigente':`substituída em ${time(r.supersededAtMsc)} por ${r.supersededByWaveId}`}</td>
              </tr>)}</tbody>
            </table>
            <pre>{JSON.stringify(microFifty?.events,null,2)}</pre>
          </details>
          <details>
            <summary>DOW 50%</summary>
            <TextField
              size="small"
              select
              label="Auditar onda 50% Dow"
              value={inspectedFifty?.wave.id ?? ''}
              onChange={(e) => setSelectedFifty(e.target.value || undefined)}
            >
              <MenuItem value="">Referencia vigente</MenuItem>
              {fifty?.references.map((r) => (
                <MenuItem key={r.wave.id} value={r.wave.id}>
                  {time(r.wave.recognizedAtMsc)} / {price(r.wave.level50)} / {fiftyState[r.state]}
                </MenuItem>
              ))}
            </TextField>
            <details>
              <summary>
                Auditoria 50% Dow: {fifty?.references.length ?? 0} ondas /{' '}
                {fifty?.events.length ?? 0} eventos
              </summary>
              <table>
                <thead>
                  <tr>
                    <th>Conclusao conhecida</th>
                    <th>Onda</th>
                    <th>Direcao</th>
                    <th>50%</th>
                    <th>Estado</th>
                    <th>Encerramento</th>
                  </tr>
                </thead>
                <tbody>
                  {fifty?.references.map((r) => (
                    <tr key={r.wave.id}>
                      <td>{time(r.wave.recognizedAtMsc)}</td>
                      <td>{r.wave.id}</td>
                      <td>{r.wave.direction}</td>
                      <td>{price(r.wave.level50)}</td>
                      <td>{fiftyState[r.state]}</td>
                      <td>{r.endedAtMsc == null ? 'Vigente' : time(r.endedAtMsc)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {fifty?.events.map((e) => (
                <p key={e.id}>
                  {time(e.atMsc)} / {e.kind} / {e.stateBefore} - {e.stateAfter} / {e.detail}
                </p>
              ))}
              <pre>{JSON.stringify(fifty?.observations, null, 2)}</pre>
            </details>
          </details>
          <details>
            <summary>Fibonacci antiga</summary>
            <details>
              <summary>
                Auditoria Fibonacci: {audit.length} versões · {lane.ambiguities.length} ambiguidades
                ({scope === 5 ? 'M5' : 'H1'})
              </summary>
              <table>
                <thead>
                  <tr>
                    <th>Decisão</th>
                    <th>Modo/direção</th>
                    <th>Máxima: preço, origem e confirmação</th>
                    <th>Mínima: preço, origem e confirmação</th>
                    <th>50%</th>
                    <th>Motivo</th>
                    <th>Âncoras anteriores → novas</th>
                  </tr>
                </thead>
                <tbody>
                  {audit.map((v) => (
                    <tr key={v.id}>
                      <td>{time(v.decidedAtMsc)}</td>
                      <td>
                        {v.mode} / {v.direction}
                      </td>
                      <td>
                        {price(v.high.price)} · {time(v.high.extremeTimeMsc)} ·{' '}
                        {time(v.high.confirmedAtMsc)}
                      </td>
                      <td>
                        {price(v.low.price)} · {time(v.low.extremeTimeMsc)} ·{' '}
                        {time(v.low.confirmedAtMsc)}
                      </td>
                      <td>{price(v.fib50)}</td>
                      <td>{reasons[v.reason] ?? v.reason}</td>
                      <td>
                        {v.previous ? `${v.previous.highId} / ${v.previous.lowId}` : 'Inicial'} →{' '}
                        {v.high.id} / {v.low.id}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {lane.ambiguities.map((a) => (
                <p key={a.id}>
                  {time(a.atMsc)} ·{' '}
                  {a.anchor
                    ? `${a.anchor.type} ${price(a.anchor.price)} (origem ${time(a.anchor.extremeTimeMsc)}, ID ${a.anchor.id})`
                    : ''}{' '}
                  · {a.detail}
                </p>
              ))}
              {lane.events.map((e) => (
                <p key={e.id}>
                  {time(e.atMsc)} · {e.kind} · {e.detail} · versão {e.versionId}
                </p>
              ))}
            </details>
            <details>
              <summary>
                Observações geométricas dos 50%: {observations.length} · não confirmam sinais
              </summary>
              <p>
                A aproximação mede distância do intervalo do candle ao nível. Teste usa
                máxima/mínima; possível rejeição/rompimento usa fechamentos consecutivos. Não se
                presume ordem intrabar. Só se observa uma versão conhecida antes da abertura do
                candle.
              </p>
              {observations.map((o) => (
                <p key={o.id}>
                  {time(o.atMsc)} · {o.kind} · versão {o.versionId} · {o.detail}
                </p>
              ))}
            </details>
          </details>
          <details>
            <summary>Suportes e resistências</summary>
            {supportDiagnostics}
          </details>
          <details>
            <summary>Dados e Replay</summary>
            {dataDiagnostics}
          </details>
        </Stack>
      </details>
    </Stack>
  );
}
