import { Button, Paper, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { MetricCard } from '@/common/components/MetricCard';
import { formatDuration } from '@/common/utils/formatters';
import { ScannerCharts } from '@/features/scanner/components/ScannerCharts';
import { mockMarketScanResponse } from '@/features/scanner/api/mockScannerData';

export function DashboardPage() {
  const { t } = useTranslation();
  const response = mockMarketScanResponse;

  return (
    <Stack spacing={3}>
      <Paper
        sx={{
          p: { xs: 3, md: 5 },
          background:
            'linear-gradient(135deg, rgba(34,211,238,.18), rgba(248,184,78,.08)), linear-gradient(145deg, #0c121b, #05080d)',
        }}
      >
        <Stack spacing={2} maxWidth={820}>
          <Typography variant="h2">{t('dashboard.title')}</Typography>
          <Typography variant="h6" color="text.secondary">
            {t('dashboard.subtitle')}
          </Typography>
          <Button component={Link} to="/scanner" variant="contained" sx={{ alignSelf: 'flex-start' }}>
            {t('dashboard.openScanner')}
          </Button>
        </Stack>
      </Paper>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
        <MetricCard title={t('dashboard.discovered')} value={response.discoveredCount} />
        <MetricCard title={t('dashboard.eligible')} value={response.eligibleCount} />
        <MetricCard title={t('dashboard.analyzed')} value={response.analyzedCount} />
        <MetricCard title={t('dashboard.qualified')} value={response.qualifiedCount} />
        <MetricCard title={t('dashboard.returned')} value={response.returnedCount} />
        <MetricCard
          title={t('dashboard.averageAnalysisTime')}
          value={formatDuration(response.averageAnalysisDurationMillis)}
        />
      </Stack>
      <ScannerCharts results={response.results} />
    </Stack>
  );
}
