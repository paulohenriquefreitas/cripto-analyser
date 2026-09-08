import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import {
  Box,
  Divider,
  Drawer,
  Grid2,
  IconButton,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useTranslation } from 'react-i18next';

import { EntryChip } from '@/common/components/EntryChip';
import { IndicatorCard } from '@/common/components/IndicatorCard';
import { ReasonList } from '@/common/components/ReasonList';
import { ScoreBadge } from '@/common/components/ScoreBadge';
import { TrendChip } from '@/common/components/TrendChip';
import { formatCurrency, formatNumber, formatPercent } from '@/common/utils/formatters';
import type { ScannerResult } from '@/features/scanner/models/scannerModels';

type AnalysisDrawerProps = {
  result: ScannerResult | null;
  onClose: () => void;
};

export function AnalysisDrawer({ result, onClose }: AnalysisDrawerProps) {
  const { t } = useTranslation();
  if (!result) return null;
  const entry = result.entryAnalysis;

  return (
    <Drawer anchor="right" open={Boolean(result)} onClose={onClose} PaperProps={{ sx: { width: { xs: '100%', md: 560 }, p: 3 } }}>
      <Stack spacing={3}>
        <Stack direction="row" alignItems="center" justifyContent="space-between">
          <Box>
            <Typography variant="h4" fontWeight={900}>
              {result.ticker}
            </Typography>
            <Typography color="text.secondary">
              {t('stock.analysisDate')}: {result.analysisDate}
            </Typography>
          </Box>
          <IconButton onClick={onClose}>
            <CloseRoundedIcon />
          </IconButton>
        </Stack>
        <Stack direction="row" spacing={1} flexWrap="wrap">
          <TrendChip trend={result.trend} />
          {entry ? <EntryChip status={entry.status} /> : null}
        </Stack>
        <Stack direction="row" spacing={3}>
          <ScoreBadge value={result.score} label={t('scanner.momentum')} />
          {entry ? <ScoreBadge value={entry.score} label={t('scanner.entry')} /> : null}
        </Stack>
        <Divider />
        <Grid2 container spacing={2}>
          <Grid2 size={6}>
            <IndicatorCard label="Last Close" value={formatCurrency(result.lastClose)} />
          </Grid2>
          <Grid2 size={6}>
            <IndicatorCard label="Current Price" value={formatCurrency(result.currentPrice)} />
          </Grid2>
          <Grid2 size={6}>
            <IndicatorCard label="RSI9" value={formatNumber(result.technicalAnalysis.rsi9)} />
          </Grid2>
          <Grid2 size={6}>
            <IndicatorCard label="ADX14" value={formatNumber(result.technicalAnalysis.adx14)} />
          </Grid2>
          <Grid2 size={6}>
            <IndicatorCard label="ATR14" value={formatNumber(result.technicalAnalysis.atr14)} />
          </Grid2>
          <Grid2 size={6}>
            <IndicatorCard label="Distance EMA21" value={formatPercent(result.technicalAnalysis.distanceFromEma21Percent)} />
          </Grid2>
        </Grid2>
        {entry ? (
          <Paper sx={{ p: 2 }}>
            <Typography variant="h6">{t('scanner.entryAnalysis')}</Typography>
            <Grid2 container spacing={1.5} sx={{ mt: 1 }}>
              {Object.entries(entry.checks).map(([key, value]) => (
                <Grid2 key={key} size={6}>
                  <Typography color={value ? 'success.main' : 'text.secondary'} variant="body2">
                    {value ? '✓' : '•'} {key}
                  </Typography>
                </Grid2>
              ))}
            </Grid2>
          </Paper>
        ) : null}
        <Paper sx={{ p: 2 }}>
          <Typography variant="h6">{t('scanner.scoreBreakdown')}</Typography>
          {entry ? (
            <Grid2 container spacing={1.5} sx={{ mt: 1 }}>
              {Object.entries(entry.scoreBreakdown).map(([key, value]) => (
                <Grid2 key={key} size={6}>
                  <Typography variant="body2">
                    {key}: <strong>{value}</strong>
                  </Typography>
                </Grid2>
              ))}
            </Grid2>
          ) : null}
        </Paper>
        <Paper sx={{ p: 2 }}>
          <Typography variant="h6">{t('scanner.reasons')}</Typography>
          <ReasonList reasons={[...result.reasons, ...(entry?.reasons ?? [])]} />
        </Paper>
      </Stack>
    </Drawer>
  );
}
