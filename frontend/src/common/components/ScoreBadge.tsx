import { Box, CircularProgress, Typography } from '@mui/material';

type ScoreBadgeProps = {
  value: number;
  label: string;
};

export function ScoreBadge({ value, label }: ScoreBadgeProps) {
  const color = value >= 80 ? 'success.main' : value >= 60 ? 'warning.main' : 'error.main';

  return (
    <Box sx={{ position: 'relative', display: 'inline-flex' }}>
      <CircularProgress variant="determinate" value={value} size={86} thickness={5} sx={{ color }} />
      <Box
        sx={{
          inset: 0,
          position: 'absolute',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexDirection: 'column',
        }}
      >
        <Typography variant="h6" fontWeight={800}>
          {value}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
      </Box>
    </Box>
  );
}
