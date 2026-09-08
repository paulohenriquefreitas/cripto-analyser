import { useQuery } from '@tanstack/react-query';

import { scannerApi } from '@/features/scanner/api/scannerApi';

export function useTickerAnalysis(ticker: string | undefined) {
  return useQuery({
    queryKey: ['ticker', ticker?.toUpperCase()],
    queryFn: () => scannerApi.getTicker(ticker ?? ''),
    enabled: Boolean(ticker),
  });
}
