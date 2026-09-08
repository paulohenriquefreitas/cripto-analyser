import { env } from '@/common/api/env';
import { httpClient } from '@/common/api/httpClient';
import type { MarketScanRequest, MarketScanResponse, ScannerResult } from '@/features/scanner/models/scannerModels';

import { mockMarketScanResponse } from './mockScannerData';

export type ScannerApi = {
  runMarketScan: (request: MarketScanRequest) => Promise<MarketScanResponse>;
  getTicker: (ticker: string) => Promise<ScannerResult>;
};

const realScannerApi: ScannerApi = {
  async runMarketScan(request) {
    const response = await httpClient.post<MarketScanResponse>('/api/panic-scanner/market-scan', request, {
      timeout: 180_000,
    });
    return response.data;
  },
  async getTicker(ticker) {
    const response = await httpClient.post<ScannerResult>(`/api/panic-scanner/analyze/${ticker}`);
    return response.data;
  },
};

const mockScannerApi: ScannerApi = {
  async runMarketScan() {
    await delay(450);
    return mockMarketScanResponse;
  },
  async getTicker(ticker) {
    await delay(250);
    const found = mockMarketScanResponse.results.find((item) => item.ticker === ticker.toUpperCase());
    return found ?? { ...mockMarketScanResponse.results[0], ticker: ticker.toUpperCase() };
  },
};

export const scannerApi = env.useMockApi ? mockScannerApi : realScannerApi;

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
