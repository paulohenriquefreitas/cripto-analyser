import { httpClient } from '@/common/api/httpClient';
import type { WinAnalysis } from '@/features/win/models/winModels';

export type WinApi = {
  getAnalysis: (contract: string, contextTimeframe: string, executionTimeframe: string) => Promise<WinAnalysis>;
};

export const winApi: WinApi = {
  async getAnalysis(contract, contextTimeframe, executionTimeframe) {
    const response = await httpClient.get<WinAnalysis>('/api/win/analysis', {
      params: { contract, contextTimeframe, executionTimeframe },
      timeout: 10_000,
    });
    return response.data;
  },
};
