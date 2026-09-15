import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControlLabel,
  Grid2 as Grid,
  LinearProgress,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';

import { ErrorPanel } from '@/common/components/ErrorPanel';
import { MetricCard } from '@/common/components/MetricCard';
import { ReasonList } from '@/common/components/ReasonList';
import { ScoreBadge } from '@/common/components/ScoreBadge';
import { useWinAnalysis } from '@/features/win/hooks/useWinAnalysis';
import {
  tradeLocationLabel,
  winExecutionStatusLabel,
  winMarketStateLabel,
  winSetupTypeLabel,
  winSignalColor,
  winSignalLabel,
} from '@/features/win/models/winLabels';
import type { WinTechnicalSnapshot } from '@/features/win/models/winModels';

const refreshIntervalMs = Number(import.meta.env.VITE_WIN_REFRESH_INTERVAL_MS ?? 3000);

export function WinScannerPage() {
  const [contract, setContract] = useState('WINV26');
  const [pollingEnabled, setPollingEnabled] = useState(true);
  const analysis = useWinAnalysis(contract, pollingEnabled ? refreshIntervalMs : 0);
  const data = analysis.data;

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" spacing={2}>
        <Box>
          <Typography variant="h3">WIN Scanner</Typography>
          <Typography color="text.secondary">
            Leitura intraday separada para WIN usando contexto de 5 min e execução de 1 min.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <TextField
            size="small"
            label="Contrato"
            value={contract}
            onChange={(event) => setContract(event.target.value.toUpperCase())}
          />
          <FormControlLabel
            control={<Switch checked={pollingEnabled} onChange={(event) => setPollingEnabled(event.target.checked)} />}
            label="Atualização contínua"
          />
          <Button startIcon={<RefreshRoundedIcon />} variant="outlined" onClick={() => analysis.refetch()}>
            Atualizar
          </Button>
        </Stack>
      </Stack>

      {analysis.isFetching ? <LinearProgress /> : null}
      {analysis.isError ? <ErrorPanel title="Falha na análise WIN" detail={analysis.error.message} /> : null}

      {data ? (
        <>
          <Paper sx={{ p: 3 }}>
            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', md: 'row' }} alignItems={{ md: 'center' }} justifyContent="space-between" spacing={2}>
                <Stack spacing={0.5}>
                  <Typography variant="overline" color="text.secondary">
                    {data.contract} | preço atual {formatNumber(data.currentPrice)} | {new Date(data.analyzedAt).toLocaleTimeString('pt-BR')}
                  </Typography>
                  <Stack direction="row" spacing={1} flexWrap="wrap">
                    <Chip color={winSignalColor[data.signal]} label={winSignalLabel[data.signal]} />
                    <Chip variant="outlined" label={winExecutionStatusLabel[data.executionStatus]} />
                    <Chip variant="outlined" label={winSetupTypeLabel[data.setupType]} />
                    <Chip variant="outlined" label={`Localização: ${tradeLocationLabel[data.tradeLocation]}`} />
                  </Stack>
                </Stack>
                <Typography variant="h3" color={data.signal === 'SELL' ? 'error.main' : data.signal === 'BUY' ? 'success.main' : 'warning.main'}>
                  {winSignalLabel[data.signal].toUpperCase()}
                </Typography>
              </Stack>
              <ReasonList reasons={data.reasons} />
            </Stack>
          </Paper>

          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 4 }}>
              <MetricCard title="Tendência 5 min" value={winMarketStateLabel[data.marketState]} helper={`ADX ${formatNumber(data.technical5m.adx14)}`} />
            </Grid>
            <Grid size={{ xs: 12, md: 4 }}>
              <MetricCard title="Setup atual" value={winExecutionStatusLabel[data.executionStatus]} helper={winSetupTypeLabel[data.setupType]} />
            </Grid>
            <Grid size={{ xs: 12, md: 4 }}>
              <MetricCard title="Qualidade da entrada" value={data.scores.execution} helper={tradeLocationLabel[data.tradeLocation]} />
            </Grid>
          </Grid>

          <Paper sx={{ p: 2.5 }}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} alignItems="center">
              <ScoreBadge value={data.scores.trend} label="Tendência" />
              <ScoreBadge value={data.scores.sell} label="Venda" />
              <ScoreBadge value={data.scores.buy} label="Compra" />
              <ScoreBadge value={data.scores.execution} label="Execução" />
              <ScoreBadge value={data.scores.sellerExhaustion} label="Exaustão V." />
              <ScoreBadge value={data.scores.buyerExhaustion} label="Exaustão C." />
            </Stack>
          </Paper>

          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 6 }}>
              <TechnicalCard title="Indicadores 5 min" technical={data.technical5m} />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TechnicalCard title="Indicadores 1 min" technical={data.technical1m} />
            </Grid>
          </Grid>

          {data.signal !== 'WAIT' && data.tradePlan.suggestedEntry ? (
            <Paper sx={{ p: 2.5 }}>
              <Typography variant="h6" gutterBottom>
                Plano de trade
              </Typography>
              <Grid container spacing={2}>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="Entrada" value={formatNumber(data.tradePlan.suggestedEntry)} /></Grid>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="Stop técnico" value={formatNumber(data.tradePlan.technicalStop)} /></Grid>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="Alvo 1" value={formatNumber(data.tradePlan.target1)} /></Grid>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="Alvo 2" value={formatNumber(data.tradePlan.target2)} /></Grid>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="Risco" value={formatNumber(data.tradePlan.riskPoints)} /></Grid>
                <Grid size={{ xs: 6, md: 2 }}><MetricCard title="R/R" value={formatNumber(data.tradePlan.rewardRiskTarget1)} /></Grid>
              </Grid>
            </Paper>
          ) : (
            <Alert severity="info">Entrada, stop e alvos só são exibidos quando há sinal acionável.</Alert>
          )}
        </>
      ) : null}
    </Stack>
  );
}

function TechnicalCard({ title, technical }: { title: string; technical: WinTechnicalSnapshot }) {
  return (
    <Paper sx={{ p: 2.5 }}>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>
      <Grid container spacing={1.5}>
        <Grid size={4}><MetricCard title="MM9" value={formatNumber(technical.sma9)} /></Grid>
        <Grid size={4}><MetricCard title="MM21" value={formatNumber(technical.sma21)} /></Grid>
        <Grid size={4}><MetricCard title="VWAP" value={formatNumber(technical.vwap)} /></Grid>
        <Grid size={4}><MetricCard title="RSI" value={formatNumber(technical.rsi9)} /></Grid>
        <Grid size={4}><MetricCard title="ADX" value={formatNumber(technical.adx14)} /></Grid>
        <Grid size={4}><MetricCard title="ATR" value={formatNumber(technical.atr14)} /></Grid>
      </Grid>
    </Paper>
  );
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-';
  return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 }).format(value);
}
