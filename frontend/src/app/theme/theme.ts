import { createTheme, type PaletteMode } from '@mui/material';

export function createAppTheme(mode: PaletteMode) {
  const isDark = mode === 'dark';

  return createTheme({
    palette: {
      mode,
      background: {
        default: isDark ? '#05080d' : '#eef3f7',
        paper: isDark ? '#0c121b' : '#ffffff',
      },
      primary: { main: '#22d3ee' },
      secondary: { main: '#f8b84e' },
      success: { main: '#22c55e' },
      warning: { main: '#f59e0b' },
      error: { main: '#ef4444' },
      divider: isDark ? 'rgba(148, 163, 184, 0.16)' : 'rgba(15, 23, 42, 0.12)',
    },
    typography: {
      fontFamily: '"IBM Plex Sans", "Segoe UI", sans-serif',
      h1: { fontWeight: 800, letterSpacing: '-0.04em' },
      h2: { fontWeight: 800, letterSpacing: '-0.035em' },
      h3: { fontWeight: 750, letterSpacing: '-0.03em' },
      button: { fontWeight: 700, textTransform: 'none' },
    },
    shape: { borderRadius: 14 },
    components: {
      MuiPaper: {
        styleOverrides: {
          root: {
            backgroundImage: isDark
              ? 'linear-gradient(145deg, rgba(18, 27, 42, 0.96), rgba(7, 12, 20, 0.96))'
              : undefined,
            border: `1px solid ${isDark ? 'rgba(148, 163, 184, 0.12)' : 'rgba(15, 23, 42, 0.08)'}`,
          },
        },
      },
      MuiButton: {
        styleOverrides: {
          root: {
            borderRadius: 12,
          },
        },
      },
    },
  });
}
