import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type PropsWithChildren, useState } from 'react';

import { createAppTheme } from '@/app/theme/theme';
import { ColorModeProvider } from '@/common/hooks/ColorModeProvider';
import { useColorMode } from '@/common/hooks/colorModeContext';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
});

function ThemedProviders({ children }: PropsWithChildren) {
  const { mode } = useColorMode();
  const [client] = useState(queryClient);

  return (
    <QueryClientProvider client={client}>
      <ThemeProvider theme={createAppTheme(mode)}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </QueryClientProvider>
  );
}

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <ColorModeProvider>
      <ThemedProviders>{children}</ThemedProviders>
    </ColorModeProvider>
  );
}
