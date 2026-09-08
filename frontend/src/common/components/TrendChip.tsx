import { Chip } from '@mui/material';
import { useTranslation } from 'react-i18next';

import { trendColor } from '@/features/scanner/models/labels';
import type { Trend } from '@/features/scanner/models/scannerModels';
import { enumLabelKey } from '@/features/scanner/services/translationKeys';

type TrendChipProps = {
  trend: Trend;
};

export function TrendChip({ trend }: TrendChipProps) {
  const { t } = useTranslation();
  return <Chip size="small" color={trendColor[trend]} label={t(enumLabelKey(trend))} />;
}
