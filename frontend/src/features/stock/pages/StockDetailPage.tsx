import { Grid2, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { EntryChip } from '@/common/components/EntryChip';
import { ErrorPanel } from '@/common/components/ErrorPanel';
import { IndicatorCard } from '@/common/components/IndicatorCard';
import { ReasonList } from '@/common/components/ReasonList';
import { ScoreBadge } from '@/common/components/ScoreBadge';
import { TrendChip } from '@/common/components/TrendChip';
import { formatCurrency, formatNumber, formatPercent } from '@/common/utils/formatters';
import { ScannerCharts } from '@/features/scanner/components/ScannerCharts';
import { useTickerAnalysis } from '@/features/scanner/hooks/useTickerAnalysis';

export function StockDetailPage() {
  const { ticker } = useParams();
  const { t } = useTranslation();
  const query = useTickerAnalysis(ticker);
  const result = query.data;

  if (query.isLoading) {
    return <Skeleton variant="rounded" height={420} />;
  }

  if (query.isError) {
    return <ErrorPanel title={t('errors.stockFailed')} detail={query.error.message} />;
  }

  if (!result) return null;

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', md: 'row' }} alignItems={{ md: 'center' }} justifyContent="space-between">
        <Stack spacing={0.5}>
          <Typography variant="h3">{result.ticker}</Typography>
          <Typography color="text.secondary">
            {t('stock.analysisDate')}: {result.analysisDate}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1}>
          <TrendChip trend={result.trend} />
          {result.entryAnalysis ? <EntryChip status={result.entryAnalysis.status} /> : null}
        </Stack>
      </Stack>
      <Paper sx={{ p: 3 }}>
        <Stack direction="row" spacing={4}>
          <ScoreBadge value={result.score} label={t('scanner.momentum')} />
          {result.entryAnalysis ? <ScoreBadge value={result.entryAnalysis.score} label={t('scanner.entry')} /> : null}
        </Stack>
      </Paper>
      <Grid2 container spacing={2}>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="Last Close" value={formatCurrency(result.lastClose)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="Current Price" value={formatCurrency(result.currentPrice)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="RSI9" value={formatNumber(result.technicalAnalysis.rsi9)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="ADX14" value={formatNumber(result.technicalAnalysis.adx14)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="ATR14" value={formatNumber(result.technicalAnalysis.atr14)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="Relative Volume" value={formatNumber(result.technicalAnalysis.relativeVolume20)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="Distance EMA21" value={formatPercent(result.technicalAnalysis.distanceFromEma21Percent)} />
        </Grid2>
        <Grid2 size={{ xs: 12, md: 3 }}>
          <IndicatorCard label="Pullback" value={formatPercent(result.entryAnalysis?.pullbackDepthPercent)} />
        </Grid2>
      </Grid2>
      <ScannerCharts results={[result]} />
      <Paper sx={{ p: 2 }}>
        <Typography variant="h6">{t('scanner.reasons')}</Typography>
        <ReasonList reasons={[...result.reasons, ...(result.entryAnalysis?.reasons ?? [])]} />
      </Paper>
    </Stack>
  );
}
