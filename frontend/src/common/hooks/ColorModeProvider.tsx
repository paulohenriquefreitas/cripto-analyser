import { type PaletteMode } from '@mui/material';
import { type PropsWithChildren, useMemo, useState } from 'react';

import { ColorModeContext, type ColorModeContextValue } from '@/common/hooks/colorModeContext';

export function ColorModeProvider({ children }: PropsWithChildren) {
  const [mode, setMode] = useState<PaletteMode>(
    () => (localStorage.getItem('panic-scanner-theme') as PaletteMode | null) ?? 'dark',
  );

  const value = useMemo<ColorModeContextValue>(
    () => ({
      mode,
      setMode: (nextMode) => {
        localStorage.setItem('panic-scanner-theme', nextMode);
        setMode(nextMode);
      },
    }),
    [mode],
  );

  return <ColorModeContext.Provider value={value}>{children}</ColorModeContext.Provider>;
}
