import axios from 'axios';

import { env } from '@/common/api/env';

export const httpClient = axios.create({
  baseURL: env.apiUrl,
  timeout: 30_000,
  headers: {
    'Content-Type': 'application/json',
  },
});
