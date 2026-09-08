import dayjs from 'dayjs';

export function formatCurrency(value: number | null | undefined) {
  if (value === null || value === undefined) return '-';
  return new Intl.NumberFormat('pt-BR', {
    style: 'currency',
    currency: 'BRL',
    maximumFractionDigits: 2,
  }).format(value);
}

export function formatNumber(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined) return '-';
  return new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value);
}

export function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '-';
  return `${formatNumber(value)}%`;
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) return '-';
  return dayjs(value).format('DD/MM/YYYY HH:mm:ss');
}

export function formatDuration(ms: number | null | undefined) {
  if (ms === null || ms === undefined) return '-';
  if (ms < 1000) return `${ms} ms`;
  return `${formatNumber(ms / 1000, 1)} s`;
}
