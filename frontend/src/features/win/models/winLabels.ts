import type { ChipProps } from '@mui/material';

import type { TradeLocation, WinExecutionStatus, WinMarketState, WinSetupType, WinSignal } from './winModels';

export const winMarketStateLabel: Record<WinMarketState, string> = {
  STRONG_UPTREND: 'Alta Forte',
  UPTREND: 'Alta',
  UPTREND_WEAKENING: 'Alta Enfraquecendo',
  CONSOLIDATION: 'Lateralização',
  DOWNTREND_WEAKENING: 'Baixa Enfraquecendo',
  DOWNTREND: 'Baixa',
  STRONG_DOWNTREND: 'Baixa Forte',
};

export const winSignalLabel: Record<WinSignal, string> = {
  BUY: 'Comprar',
  SELL: 'Vender',
  WAIT: 'Aguardar',
};

export const winExecutionStatusLabel: Record<WinExecutionStatus, string> = {
  NO_TRADE: 'Sem Operação',
  WATCH_BUY: 'Observar Compra',
  WATCH_SELL: 'Observar Venda',
  BUY_SETUP: 'Compra em Preparação',
  SELL_SETUP: 'Venda em Preparação',
  BUY_TRIGGERED: 'Entrada Compradora',
  SELL_TRIGGERED: 'Entrada Vendedora',
  OVEREXTENDED_UP: 'Alta Esticada',
  OVEREXTENDED_DOWN: 'Queda Esticada',
  REVERSAL_CANDIDATE_UP: 'Possível Reversão para Alta',
  REVERSAL_CANDIDATE_DOWN: 'Possível Reversão para Baixa',
  REVERSAL_CONFIRMED_UP: 'Reversão para Alta Confirmada',
  REVERSAL_CONFIRMED_DOWN: 'Reversão para Baixa Confirmada',
};

export const winSetupTypeLabel: Record<WinSetupType, string> = {
  NONE: 'Nenhum',
  BUY_PULLBACK: 'Pullback de Compra',
  SELL_PULLBACK: 'Pullback de Venda',
  BREAKOUT_UP: 'Rompimento para Cima',
  BREAKDOWN_DOWN: 'Rompimento para Baixo',
  WAIT_RETEST: 'Aguardar Reteste',
  FALSE_BREAKOUT_UP: 'Falso Rompimento para Cima',
  FALSE_BREAKOUT_DOWN: 'Falso Rompimento para Baixo',
  REVERSAL_UP: 'Reversão para Alta',
  REVERSAL_DOWN: 'Reversão para Baixa',
};

export const tradeLocationLabel: Record<TradeLocation, string> = {
  EXCELLENT: 'Excelente',
  GOOD: 'Boa',
  ACCEPTABLE: 'Aceitável',
  POOR: 'Ruim',
  VERY_POOR: 'Muito Ruim',
};

export const winSignalColor: Record<WinSignal, ChipProps['color']> = {
  BUY: 'success',
  SELL: 'error',
  WAIT: 'warning',
};
