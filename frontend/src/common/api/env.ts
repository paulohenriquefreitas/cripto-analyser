export const env = {
  apiUrl: import.meta.env.VITE_API_URL ?? 'http://localhost:8080',
  useMockApi: import.meta.env.VITE_USE_MOCK_API !== 'false',
};
