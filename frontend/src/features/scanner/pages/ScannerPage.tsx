import { Paper, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ErrorPanel } from '@/common/components/ErrorPanel';
import { LoadingOverlay } from '@/common/components/LoadingOverlay';
import { MetricCard } from '@/common/components/MetricCard';
import { NoData } from '@/common/components/NoData';
import { formatDuration } from '@/common/utils/formatters';
import { AnalysisDrawer } from '@/features/scanner/components/AnalysisDrawer';
import { ResultTable } from '@/features/scanner/components/ResultTable';
import { ScannerCharts } from '@/features/scanner/components/ScannerCharts';
import { ScannerFilters } from '@/features/scanner/components/ScannerFilters';
import { useMarketScan } from '@/features/scanner/hooks/useMarketScan';
import type { MarketScanRequest, ScannerResult } from '@/features/scanner/models/scannerModels';

export function ScannerPage() {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<ScannerResult | null>(null);
  const marketScan = useMarketScan();
  const response = marketScan.data;

  function handleSubmit(request: MarketScanRequest, searchTicker?: string) {
    marketScan.mutate(request, {
      onSuccess: (data) => {
        const match = searchTicker
          ? data.results.find((item) => item.ticker.includes(searchTicker))
          : undefined;
        setSelected(match ?? null);
      },
    });
  }

  return (
    <Stack spacing={3}>
      <Stack spacing={0.5}>
        <Typography variant="h3">{t('scanner.title')}</Typography>
        <Typography color="text.secondary">{t('scanner.subtitle')}</Typography>
      </Stack>
      <ScannerFilters isScanning={marketScan.isPending} onSubmit={handleSubmit} />
      {marketScan.isPending ? <LoadingOverlay message={t('scanner.loading')} /> : null}
      {marketScan.isError ? (
        <ErrorPanel title={t('errors.scanFailed')} detail={marketScan.error.message} />
      ) : null}
      {response ? (
        <>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            <MetricCard title={t('dashboard.discovered')} value={response.discoveredCount} />
            <MetricCard title={t('dashboard.eligible')} value={response.eligibleCount} />
            <MetricCard title={t('dashboard.analyzed')} value={response.analyzedCount} />
            <MetricCard title={t('dashboard.qualified')} value={response.qualifiedCount} />
            <MetricCard title={t('dashboard.breakoutReady')} value={response.breakoutReadyCount} />
            <MetricCard title={t('dashboard.returned')} value={response.returnedCount} />
            <MetricCard
              title={t('dashboard.executionTime')}
              value={formatDuration(response.totalExecutionDurationMillis)}
              helper={`${response.providerRequestCount} provider requests`}
            />
          </Stack>
          <ScannerCharts results={response.results} />
          <Paper sx={{ p: 1 }}>
            <ResultTable rows={response.results} onSelect={setSelected} />
          </Paper>
        </>
      ) : !marketScan.isPending ? (
        <NoData title={t('scanner.emptyTitle')} description={t('scanner.emptyDescription')} />
      ) : null}
      <AnalysisDrawer result={selected} onClose={() => setSelected(null)} />
    </Stack>
  );
}
