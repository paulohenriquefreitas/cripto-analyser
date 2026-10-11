import { env } from '@/common/api/env';
import { httpClient } from '@/common/api/httpClient';
import type { MarketScanResponse } from '../models/scannerModels';

export async function runExperimentalScan(): Promise<MarketScanResponse> {
  if (env.useMockApi) throw new Error('Configure VITE_USE_MOCK_API=false para consultar sinais experimentais reais.');
  const response = await httpClient.post<MarketScanResponse>(
    '/api/panic-scanner/experimental/market-scan', { limit: 50 }, { timeout: 600_000 },
  );
  return response.data;
}
