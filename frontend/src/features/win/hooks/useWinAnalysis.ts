import { useQuery } from '@tanstack/react-query';

import { toApiError } from '@/common/api/apiError';
import { winApi } from '@/features/win/api/winApi';

export function useWinAnalysis(contract: string, refreshIntervalMs: number) {
  return useQuery({
    queryKey: ['win-analysis', contract],
    queryFn: () => winApi.getAnalysis(contract, '5m', '1m'),
    refetchInterval: refreshIntervalMs,
    meta: {
      errorMapper: toApiError,
    },
  });
}
