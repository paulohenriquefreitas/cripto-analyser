import axios from 'axios';

export type ApiError = {
  title: string;
  detail: string;
  status?: number;
};

export function toApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const responseData = error.response?.data;
    const message =
      typeof responseData === 'object' && responseData !== null && 'message' in responseData
        ? String(responseData.message)
        : error.message;

    return {
      title: status ? `Request failed (${status})` : 'Network error',
      detail: sanitizeMessage(message),
      status,
    };
  }

  return {
    title: 'Unexpected error',
    detail: error instanceof Error ? sanitizeMessage(error.message) : 'Unknown failure',
  };
}

function sanitizeMessage(message: string) {
  if (message.toLowerCase().includes('exception') || message.toLowerCase().includes('stack')) {
    return 'The backend returned an unexpected error. Check server logs for details.';
  }
  return message;
}
