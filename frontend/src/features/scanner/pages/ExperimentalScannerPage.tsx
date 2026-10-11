import { Alert, Button, Chip, Paper, Stack, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { runExperimentalScan } from '../api/experimentalScannerApi';
import { env } from '@/common/api/env';

export function ExperimentalScannerPage() {
  const scan = useMutation({ mutationFn: runExperimentalScan });
  return (
    <Stack spacing={2}>
      <Typography variant="h3">Scanner experimental</Typography>
      <Alert severity="info">ANALYZE_ENTRY indica uma oportunidade de análise antecipada. A estratégia está em validação e mantém os critérios de QUALIFIED e os scores atuais. Usa o último candle diário fechado, que pode ser de um pregão anterior.</Alert>
      {env.useMockApi && <Alert severity="warning">Modo simulado ativo. Configure VITE_USE_MOCK_API=false no frontend/.env e reinicie o frontend.</Alert>}
      <Button variant="contained" disabled={scan.isPending || env.useMockApi} onClick={() => scan.mutate()}>
        {scan.isPending ? 'Analisando o mercado…' : 'Executar análise experimental agora'}
      </Button>
      {scan.isError && <Alert severity="error">{scan.error.message}</Alert>}
      {scan.data && <>
        <Typography>Consulta: {new Date(scan.data.scannedAt).toLocaleString('pt-BR')} · Analisados: {scan.data.analyzedCount} · Alertas: {scan.data.qualifiedCount} · Exibidos: {scan.data.returnedCount} (limite 50). Ordenação pelo Momentum Score atual, sem score experimental.</Typography>
        {scan.data.failures.length > 0 && <Alert severity="warning">{scan.data.failures.length} falhas de consulta: {scan.data.failures.map(f => `${f.ticker}: ${f.message}`).join('; ')}</Alert>}
        {scan.data.results.length === 0 && <Alert severity="info">Nenhum alerta experimental nos dados disponíveis nesta consulta.</Alert>}
        {scan.data.results.map(row => <Paper key={row.ticker} sx={{ p: 2 }}>
          <Stack spacing={1}>
            <Typography variant="h5">{row.ticker} · Candle fechado: {row.analysisDate}</Typography>
            <Typography>Sinal atual: {row.status} · Momentum: {row.score} · Entry Score: {row.entryAnalysis?.score ?? '—'} · Entrada atual: {row.entryAnalysis?.status ?? '—'}</Typography>
            <Stack direction="row" spacing={1} flexWrap="wrap">
              <Chip color="warning" label={row.experimentalEntry?.signal} />
              {row.experimentalEntry?.patterns.map(pattern => <Chip key={pattern} label={pattern} />)}
            </Stack>
            <Typography>Fechamento: {row.lastClose} · RSI9: {row.technicalAnalysis.rsi9} · ADX14: {row.technicalAnalysis.adx14} · Média ADX5: {row.technicalAnalysis.adxSma5 ?? '—'} · EMA9: {row.technicalAnalysis.ema9} · EMA21: {row.technicalAnalysis.ema21}</Typography>
            {row.experimentalEntry?.reasons.map((reason, index) => <Typography key={index}>{reason}</Typography>)}
            <details><summary>Checks e indicadores</summary>
              <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify({ checks: row.experimentalEntry?.checks, indicators: row.technicalAnalysis }, null, 2)}</pre>
            </details>
          </Stack>
        </Paper>)}
      </>}
    </Stack>
  );
}
