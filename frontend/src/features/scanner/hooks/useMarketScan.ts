import { useMutation, useQueryClient } from '@tanstack/react-query';

import { toApiError } from '@/common/api/apiError';
import { scannerApi } from '@/features/scanner/api/scannerApi';
import type { MarketScanRequest } from '@/features/scanner/models/scannerModels';

export function useMarketScan() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: MarketScanRequest) => scannerApi.runMarketScan(request),
    onSuccess: (response) => {
      response.results.forEach((result) => {
        queryClient.setQueryData(['ticker', result.ticker], result);
      });
    },
    meta: {
      errorMapper: toApiError,
    },
  });
}
