import { Grid2, Paper, Stack, Typography } from '@mui/material';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  XAxis,
  YAxis,
} from 'recharts';

import type { ScannerResult } from '@/features/scanner/models/scannerModels';

type ScannerChartsProps = {
  results: ScannerResult[];
};

export function ScannerCharts({ results }: ScannerChartsProps) {
  const data = results.map((result) => ({
    ticker: result.ticker,
    momentum: result.score,
    entry: result.entryAnalysis?.score ?? 0,
    rsi: result.technicalAnalysis.rsi9,
    adx: result.technicalAnalysis.adx14,
    volume: result.technicalAnalysis.relativeVolume20,
  }));

  return (
    <Grid2 container spacing={2}>
      <Grid2 size={{ xs: 12, md: 6 }}>
        <ChartShell title="Momentum x Entry">
          <AreaChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,.2)" />
            <XAxis dataKey="ticker" />
            <YAxis />
            <Area dataKey="momentum" stroke="#22d3ee" fill="#22d3ee33" />
            <Area dataKey="entry" stroke="#22c55e" fill="#22c55e33" />
          </AreaChart>
        </ChartShell>
      </Grid2>
      <Grid2 size={{ xs: 12, md: 6 }}>
        <ChartShell title="Relative Volume">
          <BarChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,.2)" />
            <XAxis dataKey="ticker" />
            <YAxis />
            <Bar dataKey="volume" fill="#f8b84e" />
          </BarChart>
        </ChartShell>
      </Grid2>
      <Grid2 size={{ xs: 12, md: 6 }}>
        <ChartShell title="RSI">
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,.2)" />
            <XAxis dataKey="ticker" />
            <YAxis />
            <Line type="monotone" dataKey="rsi" stroke="#38bdf8" strokeWidth={2} />
          </LineChart>
        </ChartShell>
      </Grid2>
      <Grid2 size={{ xs: 12, md: 6 }}>
        <ChartShell title="ADX">
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,.2)" />
            <XAxis dataKey="ticker" />
            <YAxis />
            <Line type="monotone" dataKey="adx" stroke="#a3e635" strokeWidth={2} />
          </LineChart>
        </ChartShell>
      </Grid2>
    </Grid2>
  );
}

function ChartShell({ title, children }: { title: string; children: React.ReactElement }) {
  return (
    <Paper sx={{ p: 2, height: 280 }}>
      <Stack spacing={2} height="100%">
        <Typography fontWeight={800}>{title}</Typography>
        <ResponsiveContainer width="100%" height="100%">
          {children}
        </ResponsiveContainer>
      </Stack>
    </Paper>
  );
}
