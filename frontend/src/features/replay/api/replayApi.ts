import { z } from 'zod';
import { httpClient } from '@/common/api/httpClient';

const responseSchema = z.object({
  replayId: z.string(),
  status: z.string(),
  currentTimeMsc: z.number().int(),
});

export async function createReplay(symbol: string, date: string, signal?: AbortSignal) {
  const response = await httpClient.post('/api/replay/sessions', { symbol, date }, { signal });
  return responseSchema.parse(response.data);
}
