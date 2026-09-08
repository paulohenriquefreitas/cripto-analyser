import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  TextField,
} from '@mui/material';
import { Controller, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';

import type { MarketScanRequest, RankingMode } from '@/features/scanner/models/scannerModels';

const schema = z.object({
  minimumMomentumScore: z.coerce.number().min(0).max(100).optional(),
  minimumEntryScore: z.coerce.number().min(0).max(100).optional(),
    rankingMode: z.enum(['MOMENTUM', 'ENTRY', 'BREAKOUT', 'PULLBACK']),
  limit: z.coerce.number().int().min(1).max(100),
  searchTicker: z.string().optional(),
});

export type ScannerFilterValues = z.infer<typeof schema>;

type ScannerFiltersProps = {
  isScanning: boolean;
  onSubmit: (request: MarketScanRequest, searchTicker?: string) => void;
};

export function ScannerFilters({ isScanning, onSubmit }: ScannerFiltersProps) {
  const { t } = useTranslation();
  const { control, handleSubmit, register } = useForm<ScannerFilterValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      minimumMomentumScore: 70,
      minimumEntryScore: 70,
      rankingMode: 'MOMENTUM',
      limit: 20,
      searchTicker: '',
    },
  });

  return (
    <Paper component="form" onSubmit={handleSubmit((values) => submit(values, onSubmit))} sx={{ p: 2 }}>
      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2}>
        <TextField
          label={t('scanner.filters.minimumMomentumScore')}
          type="number"
          {...register('minimumMomentumScore')}
        />
        <TextField
          label={t('scanner.filters.minimumEntryScore')}
          type="number"
          {...register('minimumEntryScore')}
        />
        <Controller
          control={control}
          name="rankingMode"
          render={({ field }) => (
            <FormControl sx={{ minWidth: 180 }}>
              <InputLabel>{t('scanner.filters.rankingMode')}</InputLabel>
              <Select {...field} label={t('scanner.filters.rankingMode')}>
                <MenuItem value="MOMENTUM">{t('enums.MOMENTUM')}</MenuItem>
                <MenuItem value="ENTRY">{t('enums.ENTRY')}</MenuItem>
                <MenuItem value="BREAKOUT">{t('enums.BREAKOUT')}</MenuItem>
                <MenuItem value="PULLBACK">{t('enums.PULLBACK')}</MenuItem>
              </Select>
            </FormControl>
          )}
        />
        <TextField label={t('scanner.filters.limit')} type="number" {...register('limit')} />
        <TextField label={t('scanner.filters.searchTicker')} {...register('searchTicker')} />
        <Button disabled={isScanning} type="submit" variant="contained" size="large">
          {isScanning ? t('scanner.actions.running') : t('scanner.actions.run')}
        </Button>
      </Stack>
    </Paper>
  );
}

function submit(values: ScannerFilterValues, onSubmit: ScannerFiltersProps['onSubmit']) {
  const request: MarketScanRequest = {
    minimumMomentumScore: values.minimumMomentumScore,
    minimumEntryScore: values.minimumEntryScore,
    rankingMode: values.rankingMode as RankingMode,
    limit: values.limit,
  };
  onSubmit(request, values.searchTicker?.trim().toUpperCase());
}
