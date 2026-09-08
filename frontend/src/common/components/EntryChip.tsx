import { Chip, Stack } from '@mui/material';
import { useTranslation } from 'react-i18next';

import { StatusIcon } from '@/common/components/StatusIcon';
import { entryStatusColor } from '@/features/scanner/models/labels';
import type { EntryStatus } from '@/features/scanner/models/scannerModels';
import { enumLabelKey } from '@/features/scanner/services/translationKeys';

type EntryChipProps = {
  status: EntryStatus;
};

export function EntryChip({ status }: EntryChipProps) {
  const { t } = useTranslation();
  return (
    <Chip
      size="small"
      color={entryStatusColor[status]}
      label={
        <Stack direction="row" alignItems="center" spacing={0.75}>
          <StatusIcon status={status} />
          <span>{t(enumLabelKey(status))}</span>
        </Stack>
      }
    />
  );
}
