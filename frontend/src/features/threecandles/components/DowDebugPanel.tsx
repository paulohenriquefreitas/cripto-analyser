import { Alert, Checkbox, FormControlLabel, Stack, Typography } from '@mui/material';
import type { DowSnapshot } from '../api/threeCandlesApi';

const states: Record<string, string> = {
  UP: 'ALTA',
  DOWN: 'BAIXA',
  UNDEFINED: 'INDEFINIDO',
  TRANSITION: 'TRANSIÇÃO',
};
const participation: Record<string, string> = {
  STRUCTURE: 'Estrutura Dow',
  LOCAL: 'Pivô local',
  SUPERSEDED: 'Terminal substituído',
};
const price = (p: number) => p.toLocaleString('pt-BR');
export function DowDebugPanel({
  snapshot,
  visible,
  labels,
  onVisible,
  onLabels,
  selectedId,
  onSelect,
  zone,
  part = 'all',
  disabled = false,
}: {
  snapshot?: DowSnapshot | null;
  visible: boolean;
  labels: boolean;
  onVisible: (v: boolean) => void;
  onLabels: (v: boolean) => void;
  selectedId?: string;
  onSelect: (id: string) => void;
  zone: string;
  part?: 'all' | 'layer' | 'labels' | 'summary' | 'selection' | 'help' | 'audit';
  disabled?: boolean;
}) {
  const time = (t: number) =>
    new Intl.DateTimeFormat('pt-BR', {
      timeZone: zone,
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(t));
  const selected = snapshot?.pivots.find((p) => p.extreme.id === selectedId);
  const lastBreak = snapshot?.events
    .filter((e) => e.kind === 'RESISTANCE_BROKEN' || e.kind === 'SUPPORT_BROKEN')
    .at(-1);
  return (
    <Stack spacing={0.5} data-testid={`dow-debug-${part}`}>
      {(part === 'all' || part === 'layer') && (
        <FormControlLabel
          label="Mostrar Dow principal"
          control={
            <Checkbox disabled={disabled} checked={visible} onChange={(_, v) => onVisible(v)} />
          }
        />
      )}
      {(part === 'all' || part === 'labels') && (
        <FormControlLabel
          label="Mostrar etiquetas HH/HL/LH/LL"
          control={
            <Checkbox disabled={disabled} checked={labels} onChange={(_, v) => onLabels(v)} />
          }
        />
      )}
      {(part === 'all' || part === 'summary') && (
        <>
          <Typography variant="body2" data-testid="dow-state">
            Dow:{' '}
            {disabled
              ? 'indisponível em M15'
              : snapshot
                ? states[snapshot.state]
                : 'Dados indisponíveis nesta resposta'}
          </Typography>
          {!disabled && lastBreak && (
            <Typography variant="caption" data-testid="dow-last-break">
              Último rompimento conhecido:{' '}
              {lastBreak.kind === 'RESISTANCE_BROKEN'
                ? 'R↑ Resistência rompida'
                : 'R↓ Suporte rompido'}{' '}
              · {time(lastBreak.atMsc)}
            </Typography>
          )}
        </>
      )}
      {(part === 'all' || part === 'help') && (
        <Typography variant="caption">
          Linhas finas: movimentos selecionados pelo motor. Pontos cheios: estrutura; círculos
          vazios: pivôs locais ou terminais substituídos. HH/HL e LH/LL comparam extremos
          estruturais do mesmo tipo. Correção isolada não confirma reversão; rompimento requer
          fechamento M5. R↑/R↓: referências rompidas por fechamento. Sem critério de lateralização
          definido. Ocultar o desenho mantém o cálculo no backend. Clique em um extremo ou consulte
          a auditoria.
        </Typography>
      )}
      {part === 'all' && visible && !snapshot && (
        <Alert severity="info">
          Estrutura Dow M5 indisponível. O backend precisa fornecer os eventos do observador
          experimental; não há classificação calculada no navegador.
        </Alert>
      )}
      {(part === 'all' || part === 'selection') && selected && (
        <Alert severity="info" data-testid="dow-selected">
          {selected.extreme.type === 'TOP' ? 'Topo' : 'Fundo'} · {price(selected.extreme.price)} ·
          origem {time(selected.extreme.extremeTimeMsc)} · confirmação{' '}
          {time(selected.extreme.confirmedAtMsc)} · {selected.classification} ·{' '}
          {participation[selected.participation]} · comparação {selected.comparisonId ?? 'nenhuma'}
          {selected.comparisonPrice !== null ? ` (${price(selected.comparisonPrice)})` : ''} ·{' '}
          {selected.reason}
        </Alert>
      )}
      {(part === 'all' || part === 'audit') && (
        <details>
          <summary>
            Auditoria Dow: {snapshot?.pivots.length ?? 0} pivôs · {snapshot?.movements.length ?? 0}{' '}
            movimentos · {snapshot?.events.length ?? 0} eventos
          </summary>
          <table>
            <thead>
              <tr>
                <th>Extremo</th>
                <th>Origem</th>
                <th>Confirmação</th>
                <th>Classe</th>
                <th>Participação</th>
                <th>Comparação</th>
                <th>Motivo</th>
                <th>Movimento vigente</th>
              </tr>
            </thead>
            <tbody>
              {snapshot?.pivots.map((p) => (
                <tr key={p.extreme.id}>
                  <td>
                    <button type="button" onClick={() => onSelect(p.extreme.id)}>
                      {p.extreme.type === 'TOP' ? 'Topo' : 'Fundo'} {price(p.extreme.price)}
                    </button>
                  </td>
                  <td>{time(p.extreme.extremeTimeMsc)}</td>
                  <td>{time(p.extreme.confirmedAtMsc)}</td>
                  <td>{p.classification}</td>
                  <td>{participation[p.participation]}</td>
                  <td>
                    {p.comparisonId ?? 'Primeiro extremo'}
                    {p.comparisonPrice !== null ? ` · ${price(p.comparisonPrice)}` : ''}
                  </td>
                  <td>{p.reason}</td>
                  <td>
                    {snapshot.movements
                      .filter((m) => m.fromId === p.extreme.id || m.toId === p.extreme.id)
                      .map((m) => m.id)
                      .join(' / ') || '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {snapshot?.events.map((e) => (
            <p key={e.id}>
              {time(e.atMsc)} · {e.kind} · {states[e.stateBefore]} → {states[e.stateAfter]} ·
              extremo {e.pivotId ?? '—'} · movimento afetado {e.affectedMovementId ?? '—'} ·{' '}
              {e.reason}
            </p>
          ))}
          <details>
            <summary>Movimentos e histórico de substituições</summary>
            <pre>
              {JSON.stringify(
                { movements: snapshot?.movements, events: snapshot?.events },
                null,
                2,
              )}
            </pre>
          </details>
        </details>
      )}
    </Stack>
  );
}
