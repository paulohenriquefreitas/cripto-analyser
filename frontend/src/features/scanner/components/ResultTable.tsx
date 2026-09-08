import { Chip, Stack, Typography } from '@mui/material';
import { DataGrid, type GridColDef, type GridRowParams } from '@mui/x-data-grid';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { EntryChip } from '@/common/components/EntryChip';
import { TrendChip } from '@/common/components/TrendChip';
import { formatCurrency, formatDateTime, formatNumber, formatPercent } from '@/common/utils/formatters';
import { entryStatusRowClass, momentumStatusColor } from '@/features/scanner/models/labels';
import type { ScannerResult } from '@/features/scanner/models/scannerModels';
import { enumLabelKey } from '@/features/scanner/services/translationKeys';

type ResultTableProps = {
  rows: ScannerResult[];
  onSelect: (result: ScannerResult) => void;
};

export function ResultTable({ rows, onSelect }: ResultTableProps) {
  const { t } = useTranslation();
  const columns = useMemo<GridColDef<ScannerResult>[]>(
    () => [
      {
        field: 'ticker',
        headerName: t('table.ticker'),
        width: 110,
        renderCell: ({ row }) => (
          <Typography fontWeight={800} sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
            {row.ticker}
          </Typography>
        ),
      },
      { field: 'score', headerName: t('table.momentumScore'), width: 140, type: 'number' },
      {
        field: 'entryScore',
        headerName: t('table.entryScore'),
        width: 120,
        type: 'number',
        valueGetter: (_, row) => row.entryAnalysis?.score ?? 0,
      },
      {
        field: 'status',
        headerName: t('table.momentumStatus'),
        width: 160,
        renderCell: ({ row }) => (
          <Chip size="small" color={momentumStatusColor[row.status]} label={t(enumLabelKey(row.status))} />
        ),
      },
      {
        field: 'entryStatus',
        headerName: t('table.entryStatus'),
        width: 210,
        valueGetter: (_, row) => row.entryAnalysis?.status ?? 'NO_ENTRY_SETUP',
        renderCell: ({ row }) => <EntryChip status={row.entryAnalysis?.status ?? 'NO_ENTRY_SETUP'} />,
      },
      {
        field: 'trend',
        headerName: t('table.trend'),
        width: 130,
        renderCell: ({ row }) => <TrendChip trend={row.trend} />,
      },
      {
        field: 'lastClose',
        headerName: t('table.lastClose'),
        width: 130,
        valueFormatter: (value: number) => formatCurrency(value),
      },
      {
        field: 'currentPrice',
        headerName: t('table.currentPrice'),
        width: 140,
        valueFormatter: (value: number) => formatCurrency(value),
      },
      {
        field: 'rsi',
        headerName: t('table.rsi'),
        width: 100,
        valueGetter: (_, row) => row.technicalAnalysis.rsi9,
        valueFormatter: (value: number) => formatNumber(value),
      },
      {
        field: 'adx',
        headerName: t('table.adx'),
        width: 100,
        valueGetter: (_, row) => row.technicalAnalysis.adx14,
        valueFormatter: (value: number) => formatNumber(value),
      },
      {
        field: 'atr',
        headerName: t('table.atr'),
        width: 100,
        valueGetter: (_, row) => row.technicalAnalysis.atr14,
        valueFormatter: (value: number) => formatNumber(value),
      },
      {
        field: 'relativeVolume',
        headerName: t('table.relativeVolume'),
        width: 150,
        valueGetter: (_, row) => row.technicalAnalysis.relativeVolume20,
        valueFormatter: (value: number) => formatNumber(value),
      },
      {
        field: 'distanceEma21',
        headerName: t('table.distanceEma21'),
        width: 160,
        valueGetter: (_, row) => row.technicalAnalysis.distanceFromEma21Percent,
        valueFormatter: (value: number) => formatPercent(value),
      },
      {
        field: 'pullback',
        headerName: t('table.pullback'),
        width: 130,
        valueGetter: (_, row) => row.entryAnalysis?.pullbackDepthPercent ?? 0,
        valueFormatter: (value: number) => formatPercent(value),
      },
      {
        field: 'updated',
        headerName: t('table.updated'),
        width: 180,
        valueGetter: (_, row) => row.marketDataUpdatedAt ?? row.analysisDate,
        valueFormatter: (value: string) => formatDateTime(value),
      },
    ],
    [t],
  );

  return (
    <DataGrid
      rows={rows}
      columns={columns}
      getRowId={(row) => row.ticker}
      autoHeight
      disableRowSelectionOnClick
      onRowClick={(params: GridRowParams<ScannerResult>) => onSelect(params.row)}
      getRowClassName={(params) => entryStatusRowClass[params.row.entryAnalysis?.status ?? 'NO_ENTRY_SETUP']}
      initialState={{
        sorting: {
          sortModel: [{ field: 'score', sort: 'desc' }],
        },
      }}
      sx={{
        border: 0,
        '& .MuiDataGrid-columnHeaders': {
          position: 'sticky',
          top: 0,
          zIndex: 2,
        },
        '& .MuiDataGrid-row': { cursor: 'pointer' },
        '& .row-entry-ready': { bgcolor: 'rgba(34, 197, 94, 0.16)' },
        '& .row-pullback-ready': { bgcolor: 'rgba(34, 197, 94, 0.16)' },
        '& .row-breakout-ready': { bgcolor: 'rgba(59, 130, 246, 0.18)' },
        '& .row-pullback-confirmed': { bgcolor: 'rgba(134, 239, 172, 0.12)' },
        '& .row-breakout-confirmed': { bgcolor: 'rgba(59, 130, 246, 0.14)' },
        '& .row-wait-breakout': { bgcolor: 'rgba(234, 179, 8, 0.12)' },
        '& .row-wait-pullback': { bgcolor: 'rgba(249, 115, 22, 0.12)' },
        '& .row-overextended': { bgcolor: 'rgba(239, 68, 68, 0.14)' },
        '& .row-invalidated, & .row-trend-weakening': { bgcolor: 'rgba(127, 29, 29, 0.28)' },
      }}
      slots={{
        noRowsOverlay: () => (
          <Stack height="100%" alignItems="center" justifyContent="center">
            <Typography color="text.secondary">{t('scanner.noResults')}</Typography>
          </Stack>
        ),
      }}
    />
  );
}
