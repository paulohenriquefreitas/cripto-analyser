import React, { type ComponentType } from 'react';
import ReactDOM from 'react-dom/client';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { createAppTheme } from '../app/theme/theme';
import { ColorModeProvider } from '../common/hooks/ColorModeProvider';
import { useColorMode } from '../common/hooks/colorModeContext';
import '../app/theme/global.css';

function ThemedPage({ Page }: { Page: ComponentType }) {
  const { mode } = useColorMode();
  return <ThemeProvider theme={createAppTheme(mode)}><CssBaseline /><Page /></ThemeProvider>;
}

/** Candle workspace has no router or React Query consumers. */
export function bootstrapCharts(Page: ComponentType) {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode><ColorModeProvider><ThemedPage Page={Page} /></ColorModeProvider></React.StrictMode>,
  );
}
