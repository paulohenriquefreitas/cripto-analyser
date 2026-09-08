import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import { Paper, Stack, Typography } from '@mui/material';

type NoDataProps = {
  title: string;
  description: string;
};

export function NoData({ title, description }: NoDataProps) {
  return (
    <Paper sx={{ p: 4, textAlign: 'center' }}>
      <Stack alignItems="center" spacing={1}>
        <SearchOffRoundedIcon color="disabled" fontSize="large" />
        <Typography variant="h6">{title}</Typography>
        <Typography color="text.secondary">{description}</Typography>
      </Stack>
    </Paper>
  );
}
