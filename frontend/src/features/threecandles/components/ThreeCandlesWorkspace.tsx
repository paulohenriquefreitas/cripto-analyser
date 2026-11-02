import {DmiPanel} from './DmiPanel';
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
import { useMemo, useState, type ReactNode, type SyntheticEvent } from 'react';
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
import { presentPatterns, patternDuplicationAudit, invalidationReason, type PatternDisplayMode } from '../models/patternPresentation';
import {ExperimentalChopPanel} from './ExperimentalChopPanel';

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
  patternMarks?: boolean;
  patternNumbers?: boolean;
  patternHighlights?: boolean;
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
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const toggleDetails = (key: string) => (event: SyntheticEvent<HTMLDetailsElement>) => {
    if (event.target !== event.currentTarget) return;
    const open = event.currentTarget.open;
    setExpanded(previous => previous[key] === open ? previous : { ...previous, [key]: open });
  };
  const [showDow, setShowDow] = useState(presentation?.current.dow ?? false),
    [dowLabels, setDowLabels] = useState(presentation?.current.dowLabels ?? false),
    [selectedDow, setSelectedDow] = useState<string>();
  const [showMicro, setShowMicro] = useState(presentation?.current.micro ?? false),
    [microLabels, setMicroLabels] = useState(presentation?.current.microLabels ?? false);
  const [allMicroFractals, setAllMicroFractals] = useState(
    presentation?.current.allMicroFractals ?? false,
  );
  const [showOpening, setShowOpening] = useState(presentation?.current.opening ?? false);
  const [showDmi,setShowDmi]=useState(true);
  const [showChop,setShowChop]=useState(false);
  const [openingMinimum, setOpeningMinimum] = useState(0);
  const [showPattern, setShowPattern] = useState(presentation?.current.patternMarks ?? false);
  const [patternNumbers, setPatternNumbers] = useState(presentation?.current.patternNumbers ?? false);
  const [patternHighlights, setPatternHighlights] = useState(presentation?.current.patternHighlights ?? false);
  const [patternFocus,setPatternFocus] = useState('');
  const [earlyAuditOpen,setEarlyAuditOpen]=useState(false);
  const [patternMode,setPatternMode] = useState<PatternDisplayMode>('MONITORING');
  const [followPattern,setFollowPattern] = useState(false);
  const [patternViewport,setPatternViewport] = useState<{from:number;to:number;request:number}>();
  const candidates=view?.patternStudy?.candidates??[];
  const reachedSequences=new Set(candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO').map(c=>[c.candle1Id,c.direction,c.trigger.level].join('|'))).size;
  const patternScope=`${view?.symbol}:${view?.patternAudit?.sessionDate}|`;
  const recognizedCandidates=candidates.filter(c=>view?.patternAudit?.patterns.some(p=>p.id===c.id));
  const selectedPattern=followPattern ? recognizedCandidates.at(-1)?.id??'' : patternFocus.startsWith(patternScope)?patternFocus.slice(patternScope.length):recognizedCandidates[0]?.id||'';
  const {snapshot:patternSnapshot,executions:hypotheticalExecutions}=presentPatterns(view?.patternAudit,view?.patternStudy,patternMode,selectedPattern);
  const duplication=useMemo(()=>patternDuplicationAudit(expanded.candidates ? candidates : []),[candidates, expanded.candidates]);
  const selectPattern=(id:string)=>{
    setPatternFocus(patternScope+id);setPatternMode('SELECTED');setFollowPattern(false);
    const c=candidates.find(c=>c.id===id);
    if(c)setPatternViewport({from:c.candle1.candle.bucketStartTimeMsc,to:(c.candle3??c.candle2??c.candle1).candle.bucketStartTimeMsc,request:Date.now()});
  };
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
    () => ({ snapshot: view?.dow, candles: view?.candles, earlyPivots: view?.structure?.earlyPivots, visible: showDow && supported, labels: dowLabels }),
    [view?.dow, view?.candles, view?.structure?.earlyPivots, showDow, dowLabels, supported],
  );
  const [showDowFifty, setShowDowFifty] = useState(presentation?.current.dowFifty ?? false),
    [selectedFifty, setSelectedFifty] = useState<string>();
  const [showMicroFifty, setShowMicroFifty] = useState(presentation?.current.microFifty ?? false);
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
  const fibonacciView = visible || compare || pick || manual.length || expanded.advanced || expanded.audit ? view : undefined;
  const analysis = useMemo(() => analyzeFibonacci(fibonacciView, tolerance), [fibonacciView, tolerance]);
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
      anchors: visible || pick ? anchors : [],
      pick,
    }),
    [primary, comparison, anchors, pick, visible],
  );
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat('pt-BR', {
    timeZone: candleDisplayTimeZone, dateStyle: 'short', timeStyle: 'short',
  }), [candleDisplayTimeZone]);
  const time = (t: number) => dateFormatter.format(new Date(t));
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
      <Stack direction="row" useFlexGap flexWrap="wrap" gap={1} role="group" aria-label="Marcações do Padrão de 3 Candles" data-testid="pattern-controls">
        <FormControlLabel label="Mostrar marcações do Padrão de 3 Candles" control={<Checkbox disabled={!supported} checked={showPattern}
          onChange={(_,v)=>{setShowPattern(v);remember('patternMarks',v);}} />} />
        <FormControlLabel label="Mostrar números 1 / 2 / 3" control={<Checkbox disabled={!supported} checked={patternNumbers}
          onChange={(_,v)=>{setPatternNumbers(v);remember('patternNumbers',v);}} />} />
        <FormControlLabel label="Mostrar destaque colorido dos candles" control={<Checkbox disabled={!supported} checked={patternHighlights}
          onChange={(_,v)=>{setPatternHighlights(v);remember('patternHighlights',v);}} />} />
      </Stack>
      <Stack direction="row" useFlexGap flexWrap="wrap" gap={1} data-testid="pattern-display-controls">
        <FormControlLabel label="Mostrar DMI / ADX (14)" control={<Checkbox disabled={!supported} checked={showDmi} onChange={(_,v)=>setShowDmi(v)}/>} />
        <FormControlLabel label="Mostrar CHOP (experimental)" control={<Checkbox disabled={!supported} checked={showChop} onChange={(_,v)=>setShowChop(v)}/>} />
        <TextField select size="small" label="Exibição dos padrões" value={patternMode} onChange={e=>{setPatternMode(e.target.value as PatternDisplayMode);setPatternViewport(undefined);}}>
          <MenuItem value="MONITORING">Em formação e gatilhos</MenuItem><MenuItem value="SETUPS">Todos os padrões com gatilho observado</MenuItem><MenuItem value="SELECTED">Padrão selecionado</MenuItem><MenuItem value="TRIGGERS">Gatilhos alcançados</MenuItem><MenuItem value="ALL">Todos os candidatos</MenuItem>
        </TextField>
        <TextField select size="small" slotProps={{ select: { native: true } }} label="Candidato do padrão (visualização)" value={candidates.some(c=>c.id===selectedPattern)?selectedPattern:''} onChange={e=>selectPattern(e.target.value)} sx={{minWidth:280,maxWidth:650}}>
          <option value="">Nenhum candidato disponível neste instante</option>
          {candidates.map((c,i)=><option key={c.id} value={c.id}>{i+1} · {c.direction==='BUY'?'Compra':'Venda'} · {time(c.candle1.candle.bucketStartTimeMsc)} · {price(c.region.low)} · {c.state}</option>)}
        </TextField>
        <FormControlLabel label="Acompanhar candidato mais recente no Replay" control={<Checkbox checked={followPattern} onChange={(_,v)=>{if(!v)setPatternFocus(patternScope+selectedPattern);setFollowPattern(v);}} />} />
      </Stack>
      <details data-testid="pattern-candidates" onToggle={toggleDetails('candidates')}>
        <summary>Candidatos do pregão ({candidates.length}) — selecionar no gráfico</summary>{expanded.candidates && <>
        <Typography variant="body2">E indica alcance hipotético, sem operação executada ou resultado financeiro. Seleção fixa acompanha as etapas conhecidas desse candidato; a opção de acompanhamento troca para o candidato mais recente.</Typography>
        <div style={{overflowX:'auto',maxHeight:420}}><table><thead><tr><th>Horário C1 / C2 / C3</th><th>Direção</th><th>Referência-alvo</th><th>NEGA</th><th>Estado</th><th>Preço E / estado</th><th>Invalidação / indeterminação</th></tr></thead>
          <tbody>{candidates.map(c=><tr key={c.id} onClick={()=>selectPattern(c.id)} aria-selected={c.id===selectedPattern} data-recognized={recognizedCandidates.some(p=>p.id===c.id)} style={{cursor:'pointer',background:c.id===selectedPattern?'rgba(56,189,248,0.14)':undefined}}>
            <td><Button size="small">{time(c.candle1.candle.bucketStartTimeMsc)}</Button><br/>{c.candle2?time(c.candle2.candle.bucketStartTimeMsc):'—'} / {c.candle3?time(c.candle3.candle.bucketStartTimeMsc):'—'}</td>
            <td>{c.direction==='BUY'?'Compra':'Venda'}</td><td>{price(c.region.low)}–{price(c.region.high)}<br/>{c.region.source}<br/>{c.region.id}</td>
            <td>{c.negationVariants.join(' + ')||'—'}</td><td>{c.state}</td><td>{c.trigger.level==null?'—':price(c.trigger.level)}<br/>{c.trigger.state}</td><td>{invalidationReason(c)||'—'}</td>
          </tr>)}</tbody></table></div>
        <details data-testid="pattern-duplicates"><summary>Auditoria de duplicidades e regra de negacao</summary>
          <Typography variant="body2">Total: {duplication.total}; sequências distintas de IDs de candles: {duplication.sequences}; sequência + direção: {duplication.sequenceDirections}. Sequências parciais usam apenas os IDs já conhecidos.</Typography>
          {duplication.sharedCandles.map((s,i)=><Typography key={i} variant="body2">Candle {i+1} compartilhado: {s.groups} grupos, {s.candidates} candidatos, {s.excess} registros excedentes ao primeiro por grupo.</Typography>)}
          <Typography variant="body2">Referências diferentes na mesma faixa exata, C1 e direção: {duplication.sameRegionGroups} grupos / {duplication.sameRegionCandidates} candidatos. Sem tolerância de agrupamento adicional.</Typography>
          <Typography variant="body2">Chegaram ao TESTA: {duplication.tested}; alcançaram E: {duplication.reached}; testes invalidados: {duplication.invalidated}; rejeitados na NEGA: {duplication.rejected}.</Typography>
          {duplication.variants.map(v=><Typography key={v.variant} variant="body2">{v.variant}: {v.total} candidatos; {v.tested} com teste; {v.invalidated} invalidados.</Typography>)}
          <Typography variant="body2">Regra V2: C2 nao continua o fechamento de C1, independentemente da cor; C1 so e marcado no fechamento de C2. C3 precisa testar na direcao anterior antes do gatilho. A/B identificam apenas dados legados. Referencias coincidentes continuam preservadas na auditoria.</Typography>
        </details>
      </>}</details>
      {pick && (
        <Alert severity="info">
          Seleção manual armada: clique em um {pick === 'TOP' ? 'topo' : 'fundo'} confirmado.{' '}
          <Button size="small" onClick={() => setPick(undefined)}>
            Cancelar captura
          </Button>
        </Alert>
      )}
      <ThreeCandlesChart
        patternViewport={patternViewport}
        controller={controller}
        zone={candleDisplayTimeZone}
        overlay={overlay}
        onPick={selectAnchor}
        dowOverlay={dowOverlay}
        microDowOverlay={microDowOverlay}
        openingOverlay={{snapshot:view?.openingStructure,visible:showOpening && supported,minimumPoints:openingMinimum}}
        dowFiftyOverlay={dowFiftyOverlay}
        microFiftyOverlay={{snapshot:microFifty,visible:showMicroFifty && supported,principalLevel}}
        patternOverlay={{snapshot:patternSnapshot,candles:view?.candles,executions:hypotheticalExecutions,visible:showPattern && supported,numbers:patternMode==='TRIGGERS'||patternNumbers,highlights:patternMode!=='TRIGGERS'&&patternHighlights,compactExecutions:patternMode==='TRIGGERS'}}
        onPickDowFifty={setSelectedFifty}
        onPickDow={(p) => setSelectedDow(p.extreme.id)}
      />
      {supported&&showDmi&&(view?.dmi?<DmiPanel snapshot={view.dmi} zone={candleDisplayTimeZone}/>:<Typography>DMI/ADX: aguardando snapshot do servidor.</Typography>)}
      {supported&&showChop&&(view?.chop?<ExperimentalChopPanel snapshot={view.chop} zone={candleDisplayTimeZone}/>:<Typography>CHOP: aguardando snapshot do servidor.</Typography>)}
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
        {supported && showDow && view?.structure?.earlyPivots && <Typography variant="body2" data-testid="early-dow-summary" sx={{color:'#F59E0B'}}>
          Dow 2+1: {view.structure.earlyPivots.filter(p=>p.status==='CANDIDATE_2_1').length} candidato(s) aguardando C3 fechar.
          {' '}C1, C2 e C3: vermelho para candidato de alta para baixa; verde para baixa para alta. Candidato provisório conhecido ao fechar C2; C3 acompanha o candle em formação. Não representa entrada.
        </Typography>}

        {supported && showDow && view?.structure?.earlyPivots && <details data-testid="early-dow-audit" onToggle={e=>{if(e.target===e.currentTarget)setEarlyAuditOpen(e.currentTarget.open);}}>
          <summary>Candidatos Dow 2+1 ({view.structure.earlyPivots.length})</summary>
          {earlyAuditOpen && <table><thead><tr><th>Extremo</th><th>Formação</th><th>Conhecido ao fechar C2</th><th>Resolução de C3</th><th>Estado</th></tr></thead><tbody>
            {view.structure.earlyPivots.map(p=><tr key={p.id}><td>{p.type==='TOP'?'Topo':'Fundo'} {price(p.price)}</td><td>{time(p.origin.candle.bucketStartTimeMsc)}</td><td>{time(p.availableAtMsc)}</td><td>{p.resolvedAtMsc==null?'Aguardando':time(p.resolvedAtMsc)}</td><td title={p.reason}>{({CANDIDATE_2_1:'Provisório 2+1',CONFIRMED_2_2:'Confirmado 2+2',INVALIDATED:'Invalidado por C3',INCONCLUSIVE_GAP:'Inconclusivo: lacuna'})[p.status]}</td></tr>)}
          </tbody></table>}
        </details>}
        {supported && showPattern && view?.patternStudy && <Typography variant="body2" data-testid="experimental-pattern-summary">
          Padrão experimental com contexto e sequencia M1 em validação: {view.patternStudy.candidates.length} candidatos por referência. {reachedSequences} sequências com gatilho observado.
          {!!view.patternStudy.parameters.minRejectionTicks && <> Pavio mínimo: {view.patternStudy.parameters.minRejectionTicks*5} pontos ({view.patternStudy.parameters.minRejectionTicks} ticks). Afastamento: {view.patternStudy.parameters.orderOffsetPoints} pontos.</>}
          {' '}E = nível hipotético alcançado após disponibilidade; não representa operação executada.
          {' '}{view.patternStudy.m1Status.startsWith('M1_AUSENTE')?'M1 ausente: teste/reação e execução permanecem indeterminados.':''}
          {' '}{view.patternStudy.m1Status==='NATIVE_M1_GAPS_INDETERMINATE'?'M1 incompleto: trechos com lacunas ficam inconclusivos; os demais continuam sendo analisados.':''}
        </Typography>}
        {supported && showPattern && view && (!view.patternAudit || view.patternAudit.rulesStatus==='PENDING_DEFINITION') &&
          <Typography variant="body2" data-testid="pattern-definition-pending">Padrão de 3 Candles: aguardando a definição das regras de Alvo, Nega e Testa. Nenhum candle classificado automaticamente.</Typography>}
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
      <details data-testid="advanced-settings" onToggle={toggleDetails('advanced')}>
        <summary>Configurações avançadas</summary>{expanded.advanced && <>
        <Stack spacing={1.5}>
          {view?.patternStudy && <details data-testid="experimental-pattern-audit">
            <summary>Detector experimental — candidatos, hipóteses e gatilhos</summary>
            <Typography variant="body2">Hipótese operacional em validação, não reprodução fiel do curso. Contagens por referência: confluências podem repetir a mesma sequência. Tolerância: {price(view.patternStudy.parameters.contactTolerancePoints)} pontos; deslocamento hipotético WIN: {price(view.patternStudy.parameters.orderOffsetPoints)} pontos. {view.patternStudy.m1Status}.</Typography>
            <table><thead><tr><th>Candidato / direção</th><th>Candles M5</th><th>Região e disponibilidade</th><th>NEGA</th><th>Teste</th><th>Gatilho hipotético</th><th>Evidência</th></tr></thead>
              <tbody>{view.patternStudy.candidates.map((c,i)=><tr key={c.id}>
                <td>{i+1} · {c.direction==='BUY'?'Compra':'Venda'}<br/>{c.state}<br/>Não validado</td>
                <td>{[c.candle1,c.candle2,c.candle3].map((b,j)=>b?<div key={j}>{j+1}: {time(b.candle.bucketStartTimeMsc)} · O {price(b.candle.open)} H {price(b.candle.high)} L {price(b.candle.low)} C {price(b.candle.close)}<br/>{[c.candle1Id,c.candle2Id,c.candle3Id][j]} · conhecido {time(b.availableAtMsc)}</div>:<div key={j}>{j+1}: ainda não conhecido</div>)}</td>
                <td>{c.region.source}<br/>{price(c.region.low)}–{price(c.region.high)}<br/>{time(c.region.availableAtMsc)}<br/>{c.region.id}</td>
                <td>{c.negationVariants.join(' + ')||'Não confirmada'}<br/>{c.context&&<>Aproximacao: {c.context.flow} ({c.context.source})<br/>Dow principal: {c.context.principalState} / micro: {c.context.microState}<br/>{c.context.anchor&&<>Pivô âncora: {c.context.anchor.type} {price(c.context.anchor.price)} ({time(c.context.anchor.extremeTimeMsc)})<br/></>}</>}Referencia C1: {c.negationComparedExtreme==null?'—':price(c.negationComparedExtreme)}</td>
                <td>{c.testState}<br/>{c.testLow==null?'':`${price(c.testLow)}–${price(c.testHigh!)}`}</td>
                <td>{c.trigger.state}<br/>{c.trigger.level==null?'':price(c.trigger.level)}<br/>{c.trigger.placedAtMsc==null?'Sem posicionamento':`Disponível ${time(c.trigger.placedAtMsc)}`}<br/>{c.trigger.reachedAtMsc==null?'':`Alcançado ${time(c.trigger.reachedAtMsc)}`}<br/>{c.rejectionWick&&<>Pavio: {price(c.rejectionWick.points)} pontos / {c.rejectionWick.ticks} ticks, conhecido {time(c.rejectionWick.availableAtMsc)}<br/></>}{c.trigger.reason}</td>
                <td><details><summary>Eventos e motivos ({c.events.length})</summary><pre>{JSON.stringify(c.events,null,2)}</pre></details></td>
              </tr>)}</tbody>
            </table>
            <details><summary>Contatos rejeitados ou inconclusivos ({view.patternStudy.rejectedContacts.length})</summary>
              <table><thead><tr><th>Conhecido em</th><th>Candle</th><th>Referência</th><th>Motivo</th></tr></thead><tbody>{view.patternStudy.rejectedContacts.map((r,i)=><tr key={i}><td>{time(r.atMsc)}</td><td>{time(r.candleTimeMsc)}</td><td>{r.referenceId}</td><td>{r.reason}</td></tr>)}</tbody></table>
            </details>
          </details>}
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
      </>}</details>
      <details data-testid="audit-diagnostics" onToggle={toggleDetails('audit')}>
        <summary>Auditoria e diagnósticos</summary>{expanded.audit && <>
        <Stack spacing={1.5}>
          <details data-testid="pattern-audit">
            <summary>Padrão de 3 Candles — etapas visuais e evidências</summary>
            <Typography variant="body2">{view?.patternAudit?.rulesStatus==='DEFINED'
              ? `Critério: ${view.patternAudit.criterionId}. Marcações de estudo; Entrada identifica somente a etapa visual.`
              : 'As regras de reconhecimento ainda não foram definidas. Não há padrões reconhecidos nesta resposta.'}</Typography>
            {view?.patternAudit?.patterns.map(p=><details key={p.id}>
              <summary>{p.direction==='BUY'?'Compra':'Venda'} · {p.state==='CANDLE_1'?'Apenas Candle 1':p.state==='CANDLES_1_2'?'Candle 1+2':'Padrão completo (visual)'} · {p.id}</summary>
              <table><thead><tr><th>Etapa</th><th>Candle M5</th><th>Referência</th><th>Reconhecida em</th><th>Motivo</th></tr></thead>
                <tbody>{p.stages.map(s=><tr key={s.number}><td>{s.number} · {s.role==='ALVO'?'Alvo':s.role==='NEGA'?'Nega':'Testa / Entrada (visual)'}</td>
                  <td>{time(s.observation.candle.bucketStartTimeMsc)}</td><td>{price(s.referencePrice)}</td><td>{time(s.availableAtMsc)}</td><td>{s.reason}</td></tr>)}</tbody>
              </table>
            </details>)}
          </details>
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
      </>}</details>
    </Stack>
  );
}
