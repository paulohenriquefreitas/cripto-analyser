import { LinearProgress, Paper, Stack, Typography } from '@mui/material';

type LoadingOverlayProps = {
  message: string;
};

export function LoadingOverlay({ message }: LoadingOverlayProps) {
  return (
    <Paper sx={{ p: 3 }}>
      <Stack spacing={2}>
        <Typography>{message}</Typography>
        <LinearProgress />
      </Stack>
    </Paper>
  );
}
