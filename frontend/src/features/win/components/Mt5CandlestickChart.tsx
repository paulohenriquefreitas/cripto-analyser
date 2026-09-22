import { Alert, Box, useTheme } from '@mui/material';
import { CandlestickSeries, LineSeries, ColorType, createChart, type ISeriesApi, type UTCTimestamp } from 'lightweight-charts';
import { useEffect, useRef, useState } from 'react';

import { vwapSegments } from '@/features/win/models/mt5Vwap';
import type { Mt5Candle } from '@/features/win/api/mt5Api';
import type { Mt5Intrabar } from '@/features/win/models/mt5Intrabar';

export function Mt5CandlestickChart({ controller }: { controller: Mt5Intrabar }) {
  const container = useRef<HTMLDivElement>(null);
  const vwapLabel = useRef<HTMLSpanElement>(null);
  const smaLabel = useRef<HTMLSpanElement>(null);
  const sma21Label = useRef<HTMLSpanElement>(null);
  const theme = useTheme();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!container.current) return;
    let chart: ReturnType<typeof createChart> | undefined;
    let detach: (() => void) | undefined;
    try {
      chart = createChart(container.current, {
        autoSize: true,
        height: 480,
        layout: {
          background: { type: ColorType.Solid, color: theme.palette.background.paper },
          textColor: theme.palette.text.secondary,
          attributionLogo: true,
        },
        grid: {
          vertLines: { color: theme.palette.divider },
          horzLines: { color: theme.palette.divider },
        },
        timeScale: { timeVisible: true, secondsVisible: false },
        localization: { locale: 'pt-BR' },
      });
      const series = chart.addSeries(CandlestickSeries, {
        upColor: theme.palette.success.main,
        downColor: theme.palette.error.main,
        borderVisible: false,
        wickUpColor: theme.palette.success.main,
        wickDownColor: theme.palette.error.main,
      });
      const smaSeries = chart.addSeries(LineSeries, {
        color: theme.palette.warning.main,
        lineWidth: 2,
        priceScaleId: 'right',
        priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
        title: 'SMA 9',
      });
      const sma21Series = chart.addSeries(LineSeries, {
        color: '#ff5252',
        lineWidth: 2,
        priceScaleId: 'right',
        priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
        title: 'SMA 21',
      });
      let vwapSeries: ISeriesApi<'Line'>[] = [];
      const showSma = (candle?: Mt5Candle) => {
        if (vwapLabel.current) vwapLabel.current.textContent = candle?.vwap == null ? '\u2014'
          : candle.vwap.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
        if (smaLabel.current) smaLabel.current.textContent = candle?.sma9 == null ? '\u2014'
          : candle.sma9.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
        if (sma21Label.current) sma21Label.current.textContent = candle?.sma21 == null ? '\u2014'
          : candle.sma21.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
      };
      const toBar = ({ time, open, high, low, close }: Mt5Candle) => ({
        time: time as UTCTimestamp, open, high, low, close,
      });
      let initialized = false;
      detach = controller.attachChart({
        setHistory(candles) {
          series.setData(candles.map(toBar));
          smaSeries.setData(candles.filter(c => c.sma9 != null).map(c => ({
            time: c.time as UTCTimestamp, value: c.sma9!,
          })));
          sma21Series.setData(candles.filter(c => c.sma21 != null).map(c => ({
            time: c.time as UTCTimestamp, value: c.sma21!,
          })));
          // Whitespace points alone do not break line series in this library.
          // Independent series guarantee no diagonal across sessions; REST only.
          vwapSeries.forEach(segment => chart?.removeSeries(segment));
          const segments = vwapSegments(candles);
          vwapSeries = segments.map(({ session, points }) => {
            const latestSession = session === candles.at(-1)?.vwapSession;
            const segment = chart!.addSeries(LineSeries, {
              color: '#29b6f6', lineWidth: 2, priceScaleId: 'right', title: 'VWAP',
              priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
              lastValueVisible: latestSession, priceLineVisible: latestSession,
            });
            segment.setData(points.map(point => ({ ...point, time: point.time as UTCTimestamp })));
            return segment;
          });
          showSma(candles.at(-1));
          if (!initialized && candles.length) {
            chart?.timeScale().fitContent();
            initialized = true;
          }
        },
        updateLast(candle) {
          // Same raw candle timestamp: updates the last bar, never appends a synthetic one.
          series.update(toBar(candle));
          if (candle.sma9 != null) smaSeries.update({ time: candle.time as UTCTimestamp, value: candle.sma9 });
          if (candle.sma21 != null) sma21Series.update({ time: candle.time as UTCTimestamp, value: candle.sma21 });
          showSma(candle);
        },
      });
    } catch {
      setFailed(true);
    }
    return () => { detach?.(); chart?.remove(); };
  }, [controller, theme]);

  return (
    <>
      <Box sx={{ color: 'warning.main', fontSize: '0.875rem', mb: 1 }}>
        SMA 9: <span ref={smaLabel}>&mdash;</span>
      </Box>
      <Box sx={{ color: '#ff5252', fontSize: '0.875rem', mb: 1 }}>
        SMA 21: <span ref={sma21Label}>&mdash;</span>
      </Box>
      <Box sx={{ color: '#29b6f6', fontSize: '0.875rem', mb: 1 }}>
        VWAP: <span ref={vwapLabel}>&mdash;</span>
        <Box component="span" sx={{ color: 'text.secondary', ml: 1, fontSize: '0.75rem' }}>
          HLC3 / volume real &middot; atualizado na consulta oficial M5
        </Box>
      </Box>
      {failed ? <Alert severity="error">Não foi possível desenhar o gráfico de candles.</Alert> : null}
      <Box ref={container} role="img" aria-label="Candlesticks WINV26 M5, horários em UTC"
        sx={{ height: 480, width: '100%', minWidth: 0 }} />
    </>
  );
}
