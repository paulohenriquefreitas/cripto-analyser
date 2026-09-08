import { Paper, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

type MetricCardProps = {
  title: string;
  value: ReactNode;
  helper?: string;
};

export function MetricCard({ title, value, helper }: MetricCardProps) {
  return (
    <Paper sx={{ p: 2.4, minHeight: 116 }}>
      <Stack spacing={1}>
        <Typography color="text.secondary" variant="body2">
          {title}
        </Typography>
        <Typography variant="h4" fontWeight={800}>
          {value}
        </Typography>
        {helper ? (
          <Typography color="text.secondary" variant="caption">
            {helper}
          </Typography>
        ) : null}
      </Stack>
    </Paper>
  );
}
