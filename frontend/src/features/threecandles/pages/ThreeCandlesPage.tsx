import {
  Alert,
  Checkbox,
  FormControlLabel,
  Button,
  MenuItem,
  Paper,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { ThreeCandlesWorkspace } from '../components/ThreeCandlesWorkspace';
import * as api from '../api/threeCandlesApi';
import { groupM5ForDisplay } from '../models/m5DisplayGroups';
import { createCandleChartController } from '../models/candleChartController';
import { candleDisplayZone, formatCandleTime, type CandleTimeBasis } from '../models/candleTime';

const errorText = (error: unknown) => {
  const response = (
    error as { response?: { status?: number; data?: { detail?: string; message?: string } } }
  )?.response;
  if (response?.status === 404)
    return (
      response.data?.detail ??
      response.data?.message ??
      'Recurso não encontrado. Confira o ativo/data ou carregue novamente a sessão de Replay.'
    );
  return response?.data?.detail ?? response?.data?.message ?? String(error);
};

export function ThreeCandlesPage() {
  const [controller] = useState(createCandleChartController);
  const [config, setConfig] = useState<api.Config>();
  const [mode, setMode] = useState<'live' | 'replay'>('live');
  const [replayMode, setReplayMode] = useState<api.ReplayMode>('CLOSED_CANDLE');
  const [symbol, setSymbol] = useState('WINZ26');
  const [minutes, setMinutes] = useState(5);
  // Presentation preferences survive workspace/session remounts, without persistent storage.
  const presentation = useRef({
    fibonacci: false,
    dow: true,
    dowFifty: true,
    dowLabels: false,
    micro: true,
    microFifty: true,
    opening: true,
    microLabels: false,
    allMicroFractals: false,
  });
  const maxLevels = 6; // Legacy API parameter; current-session M5 pivots are unlimited.
  const [showM5, setShowM5] = useState(false);
  const [m5GroupingPoints, setM5GroupingPoints] = useState(30);
  const [showH1, setShowH1] = useState(false);
  const [h1DetectionMode, setH1DetectionMode] = useState<api.H1DetectionMode>('STRICT_2_2');
  const [showStrict, setShowStrict] = useState(true);
  const [showAdditional, setShowAdditional] = useState(true);
  useEffect(() => {
    controller.setVisibility?.(showM5, showH1);
  }, [controller, showM5, showH1]);
  useEffect(() => {
    controller.setH1ComparisonVisibility?.(showStrict, showAdditional);
  }, [controller, showStrict, showAdditional]);
  useEffect(() => {
    controller.setM5Grouping?.(m5GroupingPoints);
  }, [controller, m5GroupingPoints]);
  const maxLevelsRef = useRef(maxLevels);
  maxLevelsRef.current = maxLevels;
  const [date, setDate] = useState('');
  const [startTime, setStartTime] = useState('09:00');
  const [timeBasis, setTimeBasis] = useState<CandleTimeBasis>('MT5_WALL_CLOCK');
  const [view, setView] = useState<api.CandleView>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [replayLoading, setReplayLoading] = useState(false);
  const [historyProgress, setHistoryProgress] = useState('');
  const [configError, setConfigError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const session = useRef<string | undefined>(undefined);
  const busyRef = useRef(false);
  const requestVersion = useRef(0);
  const revision = useRef(0);
  const accept = (next: api.CandleView) => {
    controller.apply(next);
    setView(next);
  };
  useEffect(() => {
    const abort = new AbortController();
    let retry: ReturnType<typeof setTimeout> | undefined;
    const loadConfig = async () => {
      try {
        const c = await api.config(abort.signal);
        if (abort.signal.aborted) return;
        setConfig(c);
        setSymbol(c.liveSymbol);
        setConfigError(undefined);
        setDate(c.sessions[0]?.date ?? new Date().toISOString().slice(0, 10));
      } catch (e) {
        if (!abort.signal.aborted) {
          setConfigError(errorText(e));
          retry = setTimeout(() => void loadConfig(), 3000);
        }
      }
    };
    void loadConfig();
    return () => {
      abort.abort();
      clearTimeout(retry);
    };
  }, []);
  useEffect(() => {
    const abort = new AbortController();
    const generation = ++revision.current;
    const previous = session.current;
    session.current = undefined;
    if (previous) void api.close(previous).catch(() => {});
    controller.reset();
    setView(undefined);
    setError(undefined);
    setLoading(false);
    setReplayLoading(false);
    if (!config)
      return () => {
        abort.abort();
      };
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      if (abort.signal.aborted) return;
      try {
        if (mode === 'live') {
          setLoading(true);
          const next = await api.live(symbol, minutes, abort.signal, maxLevelsRef.current);
          if (!abort.signal.aborted) {
            accept(next);
            setError(undefined);
          }
        } else if (session.current && !busyRef.current) {
          const ticket = requestVersion.current;
          const next = await api.replay(session.current, abort.signal, maxLevelsRef.current);
          if (
            !abort.signal.aborted &&
            generation === revision.current &&
            ticket === requestVersion.current &&
            !busyRef.current
          )
            accept(next);
        }
      } catch (e) {
        if (!abort.signal.aborted) setError(errorText(e));
      } finally {
        if (!abort.signal.aborted) {
          setLoading(false);
          timer = setTimeout(poll, mode === 'live' ? 1000 : 250);
        }
      }
    };
    void poll();
    return () => {
      abort.abort();
      clearTimeout(timer);
      revision.current++;
      const id = session.current;
      session.current = undefined;
      if (id) void api.close(id).catch(() => {});
    };
    // Recreate the source on identity changes; request generations isolate late responses.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    controller,
    config,
    mode,
    symbol,
    minutes,
    date,
    startTime,
    timeBasis,
    h1DetectionMode,
    replayMode,
  ]);
  const loadReplay = async () => {
    const generation = revision.current;
    const abort = new AbortController();
    if (replayLoading || !config) return;
    requestVersion.current++;
    busyRef.current = true;
    setReplayLoading(true);
    setError(undefined);
    try {
      const next = await api.start(
        symbol,
        date,
        startTime,
        minutes,
        abort.signal,
        timeBasis,
        maxLevelsRef.current,
        (message) => {
          if (generation === revision.current) setHistoryProgress(message);
        },
        h1DetectionMode,
        minutes === 5 ? replayMode : 'CLOSED_CANDLE',
      );
      if (generation !== revision.current) {
        if (next.id) void api.close(next.id);
        return;
      }
      requestVersion.current++;
      const previous = session.current;
      session.current = next.id;
      if (previous) void api.close(previous).catch(() => {});
      controller.reset();
      accept(next);
    } catch (e) {
      if (generation === revision.current) setError(errorText(e));
    } finally {
      busyRef.current = false;
      if (generation === revision.current) setReplayLoading(false);
    }
  };
  const command = async (action: string, speed?: number) => {
    const id = session.current;
    if (!id || busy) return;
    const generation = revision.current;
    requestVersion.current++;
    busyRef.current = true;
    setBusy(true);
    try {
      const next = await api.command(id, action, speed, maxLevelsRef.current);
      if (generation === revision.current) {
        if (action === 'RESET') controller.reset();
        accept(next);
      }
    } catch (e) {
      if (generation === revision.current) setError(errorText(e));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };
  const symbols = [
    ...new Set([
      config?.liveSymbol ?? 'WINZ26',
      ...(mode === 'replay' ? (config?.sessions.map((s) => s.symbol) ?? []) : []),
    ]),
  ];
  const last = view?.candles.at(-1);
  const comparison = view?.structure?.preparation?.experimental;
  const displayedH1 =
    view?.structure?.levels.filter(
      (l) =>
        l.timeframeMinutes === 60 &&
        showH1 &&
        (comparison?.additionalCandidates.some((a) => a.id === l.id) ? showAdditional : showStrict),
    ).length ?? 0;
  const m5Pivots = view?.structure?.levels.filter((l) => l.timeframeMinutes === 5) ?? [];
  const m5Groups = groupM5ForDisplay(
    m5Pivots,
    symbol.startsWith('WIN') ? m5GroupingPoints : 0,
    view?.availableAtMsc ?? view?.candles.at(-1)?.availableAtMsc ?? -1,
  );
  const m5AuditKey = JSON.stringify({
    scope: view?.id ?? `live:${view?.structure?.preparation?.sessionDate ?? ''}`,
    symbol: view?.symbol, tolerance: m5GroupingPoints,
    replacements: m5Groups.flatMap(g => g.replacements),
  });
  const [m5AuditError, setM5AuditError] = useState<string>();
  useEffect(() => {
    const audit = JSON.parse(m5AuditKey);
    if (!audit.symbol || !audit.replacements.length || !audit.tolerance) {
      setM5AuditError(undefined);
      return;
    }
    const abort = new AbortController();
    const at = Math.max(...audit.replacements.map((r: {atMsc: number}) => r.atMsc));
    api.archiveM5VisualAudit(audit.scope, audit.symbol, audit.tolerance, at, audit.replacements, abort.signal)
      .then(() => setM5AuditError(undefined))
      .catch(e => { if (!abort.signal.aborted) setM5AuditError(errorText(e)); });
    return () => abort.abort();
  }, [m5AuditKey]);
  const reusedM5Regions = m5Groups.filter((g) => g.movementCount > 1).length;
  const contextSessions =
    view?.id && view.structure?.preparation
      ? [
          ...new Set(
            view.candles.map((c) =>
              new Date(c.candle.bucketStartTimeMsc).toISOString().slice(0, 10),
            ),
          ),
        ].filter((d) => d < view.structure!.preparation!.sessionDate)
      : [];
  const isLoading = mode === 'replay' ? replayLoading : loading;
  const gaps =
    view?.candles.filter(
      (c, i, all) =>
        i > 0 &&
        c.candle.bucketStartTimeMsc !== all[i - 1].candle.bucketStartTimeMsc + minutes * 60_000,
    ).length ?? 0;
  const supportControls = (
    <Stack spacing={1}>
      <Stack direction="row" gap={2} alignItems="center" flexWrap="wrap">
        <TextField
          size="small"
          select
          label="Agrupamento M5 (pontos)"
          value={m5GroupingPoints}
          disabled={minutes !== 5 || !symbol.startsWith('WIN')}
          onChange={(e) => setM5GroupingPoints(Number(e.target.value))}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value={0}>Sem agrupamento</MenuItem>
          {[15, 30, 50].map((n) => (
            <MenuItem key={n} value={n}>
              {n} pontos
            </MenuItem>
          ))}
        </TextField>
        <Typography variant="caption">
          {m5Pivots.length} pivos M5 → {m5Groups.length} linhas ({showM5 ? 'visiveis' : 'ocultas'}).
        </Typography>
      </Stack>
      <Typography variant="caption">
        {m5Pivots.length - m5Groups.length} linhas substituidas por niveis M5 mais recentes.
        {reusedM5Regions} regioes com rompimentos anteriores; todos os pivos continuam na auditoria.
      </Typography>
      {comparison && (
        <Stack direction="row" useFlexGap flexWrap="wrap" gap={2}>
          <FormControlLabel
            label="Referência H1 2+2"
            control={<Checkbox checked={showStrict} onChange={(_, v) => setShowStrict(v)} />}
          />
          <FormControlLabel
            label="Adicionais H1 1+1"
            control={
              <Checkbox checked={showAdditional} onChange={(_, v) => setShowAdditional(v)} />
            }
          />
        </Stack>
      )}
      {comparison && (
        <Alert severity="warning">
          Estudo experimental, sem score ou corte de relevância:{' '}
          {comparison.strictCandidates.length} pivôs 2+2; {comparison.oneOneCandidates.length}{' '}
          candidatos 1+1, dos quais {comparison.additionalCandidates.length} adicionais. As
          confirmações 2+2 permanecem originais. Agrupamentos com largura máxima de{' '}
          {comparison.regionWidthPoints} pontos servem somente à auditoria de testes, sem ocultar
          pivôs.
        </Alert>
      )}
      {mode === 'replay' && (
        <Stack spacing={1}>
          <TextField
            size="small"
            select
            label="Detecção H1"
            value={h1DetectionMode}
            disabled={minutes !== 5 || busy || replayLoading}
            onChange={(e) => setH1DetectionMode(e.target.value as api.H1DetectionMode)}
          >
            <MenuItem value="STRICT_2_2">2+2 (referência)</MenuItem>
            <MenuItem value="EXPERIMENTAL_1_1">2+2 + 1+1 experimental</MenuItem>
          </TextField>
          <Typography variant="caption">
            Detecção H1 muda a preparação no backend e exige carregar novamente. H1 1+1 não é Dow
            micro 1+1.
          </Typography>
        </Stack>
      )}
      <Typography variant="caption">
        Agrupamento M5 é local e apenas de apresentação; não muda pivôs, Dow ou 50%.
      </Typography>
      <Typography variant="caption">
        H1: referencias fixas dos 3 pregoes anteriores, sem limite de linhas. M5: regioes de preco
        do pregao corrente; todos os pivos permanecem na memoria.
      </Typography>
    </Stack>
  );
  const supportDiagnostics = (
    <Stack spacing={1}>
      {view?.structure && (
        <Typography variant="caption">
          {view.structure.preparation
            ? `${displayedH1} niveis exibidos H1; ${showM5 ? m5Groups.length : 0} linhas M5 (${m5Pivots.length} pivos originais). Referencias H1 fixas na abertura; rompimento e distancia nao removem linhas.`
            : view.structure.supported
              ? 'Preparacao H1 indisponivel: atualize o backend.'
              : 'Referencias disponiveis no grafico M5.'}
        </Typography>
      )}
      {m5AuditError && <Alert severity="warning">Falha ao arquivar substituicoes M5 no backend: {m5AuditError}</Alert>}
      {m5Groups.length > 0 && (
        <details>
          <summary>
            Regioes M5: {m5Pivots.length} pivos originais em {m5Groups.length} linhas
          </summary>
          <Typography variant="caption">
            O pivo confirmado mais recente substitui todas as linhas M5 a uma distancia absoluta
            de ate {m5GroupingPoints} pontos, inclusive entre topo e fundo. O preco e o tipo originais
            sao preservados. Desempate: confirmacao, formacao e ID em ordem crescente; o ultimo prevalece.
            Linhas substituidas nao retornam automaticamente. Sem agrupamento exibe todos os pivos.
          </Typography>
          <table>
            <thead>
              <tr>
                <th>Tipo</th>
                <th>Linha</th>
                <th>Faixa original</th>
                <th>Movimentos</th>
                <th>Pivos associados: preco, origem, confirmacao e estado</th>
              </tr>
            </thead>
            <tbody>
              {m5Groups.map((group) => (
                <tr key={group.id}>
                  <td>{group.type === 'TOP' ? 'Topo' : 'Fundo'}</td>
                  <td>{group.price.toLocaleString('pt-BR')}</td>
                  <td>
                    {group.low.toLocaleString('pt-BR')} – {group.high.toLocaleString('pt-BR')}
                  </td>
                  <td>{group.movementCount}</td>
                  <td>
                    {group.members.map((p) => (
                      <div key={p.id} title={p.id}>
                        {p.type === 'TOP' ? 'Topo' : 'Fundo'} {p.price.toLocaleString('pt-BR')} · origem{' '}
                        {formatCandleTime(p.extremeTimeMsc, timeBasis)} · confirmacao{' '}
                        {formatCandleTime(p.confirmedAtMsc, timeBasis)} ·{' '}
                        {p.state === 'BROKEN'
                          ? `rompido em ${formatCandleTime(p.brokenAtMsc!, timeBasis)}`
                          : 'confirmado'}
                      </div>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <details>
            <summary>Historico de substituicoes visuais M5</summary>
            <table>
              <thead><tr><th>Pivo ocultado</th><th>Novo pivo</th><th>Distancia (pontos)</th><th>Substituicao</th><th>Motivo</th></tr></thead>
              <tbody>{m5Groups.flatMap(g => g.replacements).map(r => (
                <tr key={`${r.oldPivot.id}:${r.newPivot.id}`}>
                  <td title={r.oldPivot.id}>{r.oldPivot.type} {r.oldPivot.price.toLocaleString('pt-BR')}</td>
                  <td title={r.newPivot.id}>{r.newPivot.type} {r.newPivot.price.toLocaleString('pt-BR')}</td>
                  <td>{r.distancePoints}</td>
                  <td>{formatCandleTime(r.atMsc, timeBasis)}</td>
                  <td>{r.reason}</td>
                </tr>
              ))}</tbody>
            </table>
          </details>
        </details>
      )}
      {view?.structure?.preparation && (
        <details>
          <summary>Referencias H1 preparadas: preco, origem e confirmacao</summary>
          <table>
            <thead>
              <tr>
                <th>Critério</th>
                <th>Tipo</th>
                <th>Preco</th>
                <th>Origem</th>
                <th>Confirmacao</th>
                <th>Selecao</th>
              </tr>
            </thead>
            <tbody>
              {(comparison?.strictCandidates ?? view.structure.preparation.candidates).map(
                (level) => (
                  <tr key={level.id}>
                    <td>2+2</td>
                    <td>{level.type === 'TOP' ? 'Topo' : 'Fundo'}</td>
                    <td>{level.price.toLocaleString('pt-BR')}</td>
                    <td>{formatCandleTime(level.extremeTimeMsc, timeBasis)}</td>
                    <td>{formatCandleTime(level.confirmedAtMsc, timeBasis)}</td>
                    <td>
                      {view.structure!.preparation!.levels.some((l) => l.id === level.id)
                        ? 'Referencia estrutural'
                        : 'Agrupado com preco proximo'}
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        </details>
      )}
      {comparison && (
        <>
          <details>
            <summary>Candidatos H1 1+1: comparação com 2+2</summary>
            <table>
              <thead>
                <tr>
                  <th>Critério</th>
                  <th>Tipo</th>
                  <th>Preço</th>
                  <th>Origem MT5</th>
                  <th>Confirmação</th>
                  <th>Comparação</th>
                </tr>
              </thead>
              <tbody>
                {comparison.oneOneCandidates.map((l) => (
                  <tr key={l.id}>
                    <td>1+1 experimental</td>
                    <td>{l.type === 'TOP' ? 'Topo' : 'Fundo'}</td>
                    <td>{l.price.toLocaleString('pt-BR')}</td>
                    <td>{formatCandleTime(l.extremeTimeMsc, timeBasis)}</td>
                    <td>{formatCandleTime(l.confirmedAtMsc, timeBasis)}</td>
                    <td>
                      {comparison.baselineMatches[l.id]
                        ? 'Também confirmado pelo 2+2'
                        : 'Adicional 1+1'}
                      {view!.structure!.preparation!.levels.some((p) => p.id === l.id)
                        ? ' — linha própria'
                        : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
          <details>
            <summary>Avaliações 1+1: confirmações e rejeições</summary>
            <table>
              <thead>
                <tr>
                  <th>Origem</th>
                  <th>Tipo</th>
                  <th>Preço</th>
                  <th>Decisão</th>
                  <th>Motivo</th>
                </tr>
              </thead>
              <tbody>
                {comparison.evaluations.map((e) => (
                  <tr key={`${e.originMsc}:${e.type}`}>
                    <td>{formatCandleTime(e.originMsc, timeBasis)}</td>
                    <td>{e.type}</td>
                    <td>{e.price}</td>
                    <td>{e.decision}</td>
                    <td>{e.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
          <details>
            <summary>Regiões e episódios de teste H1 (sem gerar novos pivôs por toque)</summary>
            <Typography variant="caption">
              Horas consecutivas na faixa contam como um episódio. Reações máximas nas duas H1
              seguintes disponíveis antes da abertura; podem atravessar sessões. São medidas
              históricas, sem classificação por score.
            </Typography>
            {comparison.regions.map((r) => (
              <div key={r.id}>
                <p>
                  {r.low.toLocaleString('pt-BR')}–{r.high.toLocaleString('pt-BR')}:{' '}
                  {r.pivotIds.length} pivôs, {r.tests.length} episódios.
                </p>
                <table>
                  <thead>
                    <tr>
                      <th>Início</th>
                      <th>Fim</th>
                      <th>Horas</th>
                      <th>Alta após teste</th>
                      <th>Baixa após teste</th>
                      <th>Medido até</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.tests.map((t) => (
                      <tr key={t.startMsc}>
                        <td>{formatCandleTime(t.startMsc, timeBasis)}</td>
                        <td>{formatCandleTime(t.endMsc, timeBasis)}</td>
                        <td>{t.hours}</td>
                        <td>{t.reactionUpPoints}</td>
                        <td>{t.reactionDownPoints}</td>
                        <td>{formatCandleTime(t.measuredThroughMsc, timeBasis)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </details>
        </>
      )}
    </Stack>
  );
  const dataDiagnostics = (
    <Stack spacing={1}>
      {mode === 'replay' && config && (
        <Typography variant="caption">
          Sessões locais deste ativo:{' '}
          {config.sessions
            .filter((s) => s.symbol === symbol)
            .slice(0, 12)
            .map((s) => s.date)
            .join(', ') || 'nenhuma'}
          . Datas fora desta lista serão consultadas no MT5. WIN$N é a série contínua do terminal,
          sem conversão para contratos com vencimento.
        </Typography>
      )}
      {view?.structure?.preparation && (
        <Alert severity={view.structure.preparation.sufficient ? 'info' : 'warning'}>
          Referencias H1 para {view.structure.preparation.sessionDate}:{' '}
          {view.structure.preparation.sourceSessions.join(', ')}.{' '}
          {view.structure.preparation.candidates.length} pivos confirmados antes da abertura;{' '}
          {view.structure.preparation.levels.length} linhas;{' '}
          {comparison
            ? '2+2 preservado, adicionais 1+1 agrupados somente em preços idênticos.'
            : 'agrupamento em até 5 pontos.'}
          {!view.structure.preparation.sufficient &&
            ' Historico incompleto: faltam 3 pregoes ou aquecimento anterior.'}
        </Alert>
      )}
      {view?.id && view.structure?.preparation && (
        <Alert severity={contextSessions.length < 3 ? 'warning' : 'info'}>
          Contexto M5: {contextSessions.join(', ') || 'nenhum pregão anterior disponível'}. Replay
          de {view.structure.preparation.sessionDate}; candles anteriores já conhecidos.
          {contextSessions.length < 3 &&
            ' O histórico fornecido é insuficiente para exibir três pregões anteriores. Nenhum candle foi criado para preencher a ausência.'}{' '}
          Use o zoom e a rolagem horizontal para examinar as sessões anteriores; os horários
          originais e os intervalos entre sessões foram preservados.
        </Alert>
      )}
      {view?.replayMode === 'PROGRESSIVE_M1' && !!view.missingM1Times?.length && (
        <Alert severity="warning">
          Fonte M1 com {view.missingM1Times.length} minuto(s) interno(s) ausente(s):{' '}
          {view.missingM1Times.map((t) => formatCandleTime(t, timeBasis)).join(', ')}. Esses minutos
          não geram passos nem candles artificiais. Os M1 disponíveis reproduzem o OHLC M5 oficial.
        </Alert>
      )}
      <Typography>
        Status: {isLoading ? 'Carregando' : (view?.status ?? 'Aguardando')} · Último candle:{' '}
        {last ? formatCandleTime(last.candle.bucketStartTimeMsc, timeBasis) : '—'}
        {last ? (last.closed ? ' · Fechado' : ' · Em formação') : ''} · Lacunas entre candles:{' '}
        {gaps}
      </Typography>
      <Typography variant="caption">
        {timeBasis === 'MT5_WALL_CLOCK'
          ? 'Horários civis do terminal MT5, sem conversão de fuso.'
          : 'Timestamps interpretados como UTC e exibidos em America/Sao_Paulo.'}{' '}
        Valores originais preservados; a referência da fonte deve ser validada.
        {mode === 'live'
          ? 'Tempo Real: atualizações do feed compartilhado.'
          : view?.replayMode === 'PROGRESSIVE_M1'
            ? 'Replay progressivo: 1 M1 fechado por segundo em 1x; M5 parcial atualizado a cada minuto conhecido. Sem trajetória de ticks simulada.'
            : 'Replay de candles fechados: 1 candle por segundo em 1x, sem simulação intrabar.'}{' '}
        Nenhum sinal operacional nesta etapa.
      </Typography>
    </Stack>
  );
  const sessionSummary = (
    <Stack direction="row" useFlexGap flexWrap="wrap" gap={1} data-testid="session-summary">
      <Typography variant="body2">
        {mode === 'replay' ? 'Replay' : 'Fonte'}:{' '}
        {isLoading
          ? 'Carregando'
          : view?.status === 'COMPLETED'
            ? 'FINISHED'
            : (view?.status ?? 'Aguardando')}
      </Typography>
      <Typography variant="body2">
        {mode === 'replay' ? 'Instante do Replay' : 'Instante observado'}:{' '}
        {view?.availableAtMsc != null ? formatCandleTime(view.availableAtMsc, timeBasis) : '—'}
      </Typography>
      <Typography variant="body2">
        Candle M{minutes}: {last ? (last.closed ? 'Fechado' : 'Em formação') : 'Aguardando'}
        {last ? ` · origem ${formatCandleTime(last.candle.bucketStartTimeMsc, timeBasis)}` : ''}
      </Typography>
    </Stack>
  );
  return (
    <Stack spacing={1.5} sx={{ minWidth: 0 }} data-testid="three-candles-page">
      <Stack direction="row" useFlexGap flexWrap="wrap" alignItems="baseline" gap={1.5}>
        <Typography variant="h5" component="h1">
          Padrão de 3 Candles
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {symbol} · M{minutes} ·{' '}
          {mode === 'live'
            ? 'Tempo Real'
            : replayMode === 'PROGRESSIVE_M1' && minutes === 5
              ? 'Replay progressivo M1'
              : 'Replay por candle fechado'}
        </Typography>
      </Stack>
      <Stack
        spacing={1}
        sx={{ '& .MuiInputBase-root': { minHeight: 40 }, '& .MuiToggleButton-root': { py: 1 } }}
      >
        <Stack direction="row" gap={2} flexWrap="wrap">
          <TextField
            size="small"
            select
            label="Ativo"
            value={symbol}
            onChange={(e) => {
              const nextSymbol = e.target.value;
              setSymbol(nextSymbol);
              if (mode === 'replay') {
                const available = config?.sessions.filter((s) => s.symbol === nextSymbol) ?? [];
                if (!date && available[0]) setDate(available[0].date);
              }
            }}
            sx={{ minWidth: 140 }}
          >
            {symbols.map((s) => (
              <MenuItem key={s} value={s}>
                {s}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            select
            label="Período"
            value={minutes}
            onChange={(e) => setMinutes(Number(e.target.value))}
          >
            {[5, 15].map((m) => (
              <MenuItem key={m} value={m}>
                M{m}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            select
            label="Referência do horário"
            value={timeBasis}
            onChange={(e) => setTimeBasis(e.target.value as CandleTimeBasis)}
          >
            <MenuItem value="MT5_WALL_CLOCK">Hora do terminal MT5</MenuItem>
            <MenuItem value="UTC_INSTANT">Timestamp UTC → Brasília</MenuItem>
          </TextField>
          <ToggleButtonGroup
            exclusive
            value={mode}
            onChange={(_, next: 'live' | 'replay' | null) => {
              if (next) {
                if (next === 'live' && config) setSymbol(config.liveSymbol);
                if (next === 'replay' && config?.sessions[0]) {
                  setSymbol(config.sessions[0].symbol);
                  setDate(config.sessions[0].date);
                }
                setMode(next);
              }
            }}
          >
            <ToggleButton value="live">Tempo Real</ToggleButton>
            <ToggleButton value="replay">Replay</ToggleButton>
          </ToggleButtonGroup>
        </Stack>
        {mode === 'replay' && (
          <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
            <TextField
              size="small"
              select
              label="Modo de Replay"
              value={minutes === 5 ? replayMode : 'CLOSED_CANDLE'}
              disabled={busy || replayLoading}
              onChange={(e) => setReplayMode(e.target.value as api.ReplayMode)}
            >
              <MenuItem value="CLOSED_CANDLE">Replay por candle fechado</MenuItem>
              <MenuItem value="PROGRESSIVE_M1" disabled={minutes !== 5}>
                Replay progressivo M1 (experimental)
              </MenuItem>
            </TextField>
            <TextField
              size="small"
              type="date"
              label="Data"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              size="small"
              type="time"
              label={
                timeBasis === 'MT5_WALL_CLOCK' ? 'Hora inicial (MT5)' : 'Hora inicial (Brasília)'
              }
              value={startTime}
              onChange={(e) => setStartTime(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <Button
              disabled={replayLoading || busy || !config || !date || !startTime}
              onClick={() => void loadReplay()}
            >
              {replayLoading ? 'Carregando replay…' : 'Carregar'}
            </Button>
          </Stack>
        )}
      </Stack>
      <Stack
        direction="row"
        useFlexGap
        flexWrap="wrap"
        gap={1}
        alignItems="center"
        role="group"
        aria-label="Controles de reprodução"
      >
        {mode === 'replay' && (
          <>
            <Button disabled={!view?.id || busy || isLoading} onClick={() => void command('RESET')}>
              ◀ Reiniciar
            </Button>
            <Button
              disabled={
                !view?.id ||
                busy ||
                isLoading ||
                view.status === 'COMPLETED' ||
                view.status === 'PLAYING'
              }
              onClick={() => void command('PLAY')}
            >
              ▶ Play
            </Button>
            <Button disabled={!view?.id || busy || isLoading} onClick={() => void command('PAUSE')}>
              ⏸ Pause
            </Button>
            <Button
              disabled={!view?.id || busy || isLoading || view.status === 'COMPLETED'}
              onClick={() => void command('NEXT')}
            >
              ▶| {view?.replayMode === 'PROGRESSIVE_M1' ? 'Próximo M1' : 'Próximo candle'}
            </Button>
            <TextField
              size="small"
              select
              label="Velocidade"
              value={view?.speed ?? 1}
              disabled={!view?.id || busy || isLoading}
              onChange={(e) => void command('SPEED', Number(e.target.value))}
            >
              {[1, 2, 5, 10].map((s) => (
                <MenuItem key={s} value={s}>
                  {s}x
                </MenuItem>
              ))}
            </TextField>
          </>
        )}
        <Button
          onClick={() => controller.fitChart()}
          disabled={!view?.candles.length}
          sx={{ alignSelf: 'flex-start' }}
        >
          Ajustar gráfico
        </Button>
      </Stack>
      <Stack spacing={1} data-testid="session-alerts">
        {!config && (
          <Alert severity={configError ? 'warning' : 'info'}>
            {configError
              ? `Não foi possível obter a configuração: ${configError}. Nova tentativa automática em 3 segundos.`
              : 'Obtendo configuração e sessões disponíveis…'}
          </Alert>
        )}
        {replayLoading && historyProgress && <Alert severity="info">{historyProgress}</Alert>}
        {view?.replayMode === 'PROGRESSIVE_M1' && !!view.missingM1Times?.length && (
          <Alert severity="warning">
            Fonte M1: {view.missingM1Times.length} minuto(s) interno(s) ausente(s), sem
            preenchimento artificial. Detalhes em Dados e Replay.
          </Alert>
        )}
        {error && <Alert severity="error">{error}</Alert>}
        {view && !view.structure && (
          <Alert severity="warning">
            O backend ainda não enviou os níveis estruturais. Reinicie-o com a versão atual desta
            branch.
          </Alert>
        )}
        {view?.status === 'UNAVAILABLE' && (
          <Alert severity="warning">
            Stream de ticks indisponível. Candles preservados; reconexão automática.
          </Alert>
        )}
        {view?.status === 'COMPLETED' && (
          <Alert severity="info">Fim dos candles disponíveis.</Alert>
        )}
        {minutes === 15 && (
          <Alert severity="info">
            M15: Dow principal, Dow micro, DOW 50% e Replay progressivo estão disponíveis somente em
            M5. Os candles M15 continuam disponíveis.
          </Alert>
        )}
        {minutes === 5 &&
          view?.structure &&
          !view.structure.preparation &&
          view.structure.supported && (
            <Alert severity="warning">
              Preparação H1 indisponível nesta resposta. Confira a versão do backend em Auditoria e
              diagnósticos.
            </Alert>
          )}
        {minutes === 5 &&
          view?.structure?.preparation &&
          (!view.structure.preparation.sufficient || (!!view.id && contextSessions.length < 3)) && (
            <Alert severity="warning">
              Histórico de contexto insuficiente: faltam pregões anteriores ou aquecimento H1.
              Detalhes em Dados e Replay; nenhum candle artificial foi criado.
            </Alert>
          )}
      </Stack>
      <Paper sx={{ p: { xs: 1, md: 1.5 }, minWidth: 0 }}>
        <ThreeCandlesWorkspace
          key={`${mode}:${symbol}:${date}:${minutes}:${view?.id ?? 'live'}`}
          view={view}
          controller={controller}
          presentation={presentation}
          candlesOnly
          structuralLevels={minutes === 5}
          visibleCandleViewport
          candleDisplayTimeZone={candleDisplayZone(timeBasis)}
          layerControls={
            <>
              <FormControlLabel
                label="H1 - fúcsia"
                control={
                  <Checkbox
                    disabled={minutes !== 5}
                    checked={showH1}
                    onChange={(_, checked) => setShowH1(checked)}
                    sx={{ color: '#FF00FF', '&.Mui-checked': { color: '#FF00FF' } }}
                  />
                }
              />
              <FormControlLabel
                label="M5 - amarelo"
                control={
                  <Checkbox
                    disabled={minutes !== 5}
                    checked={showM5}
                    onChange={(_, checked) => setShowM5(checked)}
                    sx={{ color: '#EAB308', '&.Mui-checked': { color: '#EAB308' } }}
                  />
                }
              />
            </>
          }
          supportControls={supportControls}
          supportDiagnostics={supportDiagnostics}
          dataDiagnostics={dataDiagnostics}
          sessionSummary={sessionSummary}
        />
      </Paper>
    </Stack>
  );
}
