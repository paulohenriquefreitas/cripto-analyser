import { z } from 'zod';
import { httpClient } from '@/common/api/httpClient';

const responseSchema = z.object({
  replayId: z.string(),
  status: z.string(),
  currentTimeMsc: z.number().int(),
});

export async function createReplay(symbol: string, date: string, signal?: AbortSignal) {
  // Creating a replay may export and validate the full trading day's MT5 history.
  const response = await httpClient.post('/api/replay/sessions', { symbol, date }, {
    signal,
    timeout: 5 * 60_000,
  });
  return responseSchema.parse(response.data);
}
