import { type PaletteMode } from '@mui/material';
import { createContext, useContext } from 'react';

export type ColorModeContextValue = {
  mode: PaletteMode;
  setMode: (mode: PaletteMode) => void;
};

export const ColorModeContext = createContext<ColorModeContextValue | null>(null);

export function useColorMode() {
  const context = useContext(ColorModeContext);
  if (!context) {
    throw new Error('useColorMode must be used inside ColorModeProvider');
  }
  return context;
}
