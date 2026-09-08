import { Paper, Stack, Typography } from '@mui/material';

type IndicatorCardProps = {
  label: string;
  value: string;
  tone?: 'neutral' | 'positive' | 'negative';
};

export function IndicatorCard({ label, value, tone = 'neutral' }: IndicatorCardProps) {
  const color = tone === 'positive' ? 'success.main' : tone === 'negative' ? 'error.main' : 'text.primary';

  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={0.5}>
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
        <Typography variant="h6" color={color} fontWeight={800}>
          {value}
        </Typography>
      </Stack>
    </Paper>
  );
}
