import { tradeLabel, type ReplayTradeMarker } from '@/features/replay/api/replayTradeStore';
import { Alert, Box, useTheme } from '@mui/material';
import {
  CandlestickSeries, LineSeries, ColorType, createChart, createSeriesMarkers,
  type ISeriesApi, type SeriesMarker, type UTCTimestamp,
} from 'lightweight-charts';
import { useEffect, useRef, useState } from 'react';

import { vwapSegments } from '@/features/win/models/mt5Vwap';
import type { Mt5Candle } from '@/features/win/api/mt5Api';
import type {
  ChartSink, ReplaySetupMarker, Ema9ReversalMarker,
} from '@/features/win/models/mt5Intrabar';

export type Mt5ChartController = { attachChart: (sink: ChartSink) => () => void };

export function setup91Details(marker: ReplaySetupMarker): string {
  const signal = marker.signal;
  if (!signal) return marker.setupType;
  const time = (msc: number) => new Date(msc).toISOString();
  const candle = signal.candle;
  return [marker.setupType,
    `Event time: ${time(marker.timeMsc!)} | LAST sequence: ${marker.sequence}`,
    `Signal candle: ${time(candle.bucketStartTimeMsc)}`,
    `OHLC: ${candle.open} / ${candle.high} / ${candle.low} / ${candle.close}`,
    `EMA9 at signal: ${signal.ema9}`,
    `Previous 5 EMA9 (oldest first): ${signal.previousFiveEma9.join(', ')}`,
    `Previous EMA classification: ${signal.previousFiveDirection}`,
    `Previous EMA slope: ${signal.previousSlope} | Current EMA slope: ${signal.currentSlope}`,
    ...(marker.breakoutPrice != null ? [`Breakout LAST: ${marker.breakoutPrice}`] : []),
  ].join('\n');
}

export function Mt5CandlestickChart({ controller, setup91Only = false }: {
  controller: Mt5ChartController;
  setup91Only?: boolean;
}) {
  const container = useRef<HTMLDivElement>(null);
  const vwapLabel = useRef<HTMLSpanElement>(null);
  const smaLabel = useRef<HTMLSpanElement>(null);
  const sma21Label = useRef<HTMLSpanElement>(null);
  const setupDetails = useRef<HTMLPreElement>(null);
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
        timeScale: { timeVisible: true, secondsVisible: false, barSpacing: 8, rightOffset: 5 },
        localization: { locale: 'pt-BR' },
      });
      const series = chart.addSeries(CandlestickSeries, {
        upColor: theme.palette.success.main,
        downColor: theme.palette.error.main,
        borderVisible: false,
        wickUpColor: theme.palette.success.main,
        wickDownColor: theme.palette.error.main,
      });
      const markerApi = createSeriesMarkers(series, []);
      const setupMarkers = new Map<string, SeriesMarker<UTCTimestamp>>();
      const tradeMarkers = new Map<string, ReplayTradeMarker>();
      const setup91Markers = new Map<string, ReplaySetupMarker>();
      const emaReversals = new Map<number, Ema9ReversalMarker>();
      chart.subscribeCrosshairMove(param => {
        if (!setupDetails.current) return;
        const reversal = emaReversals.get(Number(param.time));
        setupDetails.current.textContent = reversal
          ? `MME9: inversão para ${reversal.direction === 'UP' ? 'alta' : 'baixa'}\nCandle: ${new Date(reversal.time * 1000).toISOString()}\nMME9 anterior: ${reversal.previousValue} | Atual: ${reversal.value}\nConfirmado após fechamento: ${new Date(reversal.availableAtTimeMsc).toISOString()}`
          : [...setup91Markers.values()]
          .filter(marker => marker.time === Number(param.time))
          .map(setup91Details).concat([...tradeMarkers.values()]
            .filter(marker => marker.time === Number(param.time))
            .map(marker => `${tradeLabel(marker)} | ${marker.tradeId}\nUTC: ${new Date(marker.timeMsc).toISOString()}\nPreco: ${marker.price} | Entrada: ${marker.entry} | Stop inicial: ${marker.initialStop} | R: ${marker.risk} | Stop saldo: ${marker.stop}\nSaida: ${marker.exit ?? '-'} | Pontos: ${marker.points ?? '-'}${marker.stage === 'PARTIAL' ? ' (contribuicao de 50%)' : ''}`)).join('\n\n');
      });
      const publishMarkers = () => {
        markerApi.setMarkers(
        [...setupMarkers.values()].sort((left, right) => Number(left.time) - Number(right.time)).map(marker => ({ ...marker, text: '' })),
        );
      };
      const addReplaySetupMarker = (marker: ReplaySetupMarker) => {
        // Execution events remain available as data, but never place direction arrows.
        setup91Markers.set(marker.eventId, marker);
      };
      const addEma9Reversal = (marker: Ema9ReversalMarker) => {
        if (emaReversals.has(marker.time)) return;
        emaReversals.set(marker.time, marker);
        const down = marker.direction === 'DOWN';
        const id = `EMA9_REVERSAL-${marker.time}`;
        setupMarkers.set(id, {
          id, time: marker.time as UTCTimestamp,
          position: down ? 'aboveBar' : 'belowBar',
          color: down ? theme.palette.error.main : theme.palette.success.main,
          shape: down ? 'arrowDown' : 'arrowUp', text: '', size: 0.65,
        });
        publishMarkers();
      };
      const addReplayTradeMarker = (marker: ReplayTradeMarker) => {
        if (tradeMarkers.has(marker.eventId)) return;
        tradeMarkers.set(marker.eventId, marker);
        // Keep trade management in data/hover details; plot only closed EMA9 reversal arrows.
      };
      const smaSeries = setup91Only ? undefined : chart.addSeries(LineSeries, {
        color: theme.palette.success.main,
        lineWidth: 2,
        priceScaleId: 'right',
        priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
        title: 'MM9',
      });
      const emaSeries = chart.addSeries(LineSeries, {
        color: '#2e7d32', lineWidth: 1, priceScaleId: 'right', title: 'EMA9 (closed)',
        priceLineVisible: false,
      });
      const sma21Series = setup91Only ? undefined : chart.addSeries(LineSeries, {
        color: '#ff5252',
        lineWidth: 2,
        priceScaleId: 'right',
        priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
        title: 'MM21',
      });
      let vwapSeries: ISeriesApi<'Line'>[] = [];
      const vwapBySession = new Map<string, ISeriesApi<'Line'>>();
      const updateVwap = (candle: Mt5Candle) => {
        if (setup91Only) return;
        if (candle.vwapSession && candle.vwap != null) {
          let segment = vwapBySession.get(candle.vwapSession);
          if (!segment) {
            segment = chart!.addSeries(LineSeries, {
              color: theme.palette.warning.main, lineWidth: 2, priceScaleId: 'right', title: 'VWAP',
              priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
            });
            vwapBySession.set(candle.vwapSession, segment);
            vwapSeries.push(segment);
          }
          segment.update({ time: candle.time as UTCTimestamp, value: candle.vwap });
        }
      };
      const showSma = (candle?: Mt5Candle) => {
        if (vwapLabel.current) vwapLabel.current.textContent = candle?.vwap == null ? '—'
          : candle.vwap.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
        if (smaLabel.current) smaLabel.current.textContent = candle?.sma9 == null ? '—'
          : candle.sma9.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
        if (sma21Label.current) sma21Label.current.textContent = candle?.sma21 == null ? '—'
          : candle.sma21.toLocaleString('pt-BR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
      };
      const toBar = ({ time, open, high, low, close }: Mt5Candle) => ({
        time: time as UTCTimestamp, open, high, low, close,
      });
      let initialized = false;
      const initializeViewport = () => {
        if (initialized) return;
        // Keep normal candle spacing even when the replay has only one bar.
        chart?.timeScale().applyOptions({ barSpacing: 8, rightOffset: 5 });
        chart?.timeScale().scrollToPosition(5, false);
        initialized = true;
      };
      detach = controller.attachChart({
        setHistory(candles) {
          // Bucket updates replace candle history too; only an explicit reset clears setups.
          if (candles.length === 0) {
            initialized = false;
            setupMarkers.clear();
            setup91Markers.clear();
            emaReversals.clear();
            tradeMarkers.clear();
            if (setupDetails.current) setupDetails.current.textContent = '';
            markerApi.setMarkers([]);
          }
          series.setData(candles.map(toBar));
          emaSeries.setData(candles.filter(c => c.ema9 != null).map(c => ({
            time: c.time as UTCTimestamp, value: c.ema9!,
          })));
          smaSeries?.setData(candles.filter(c => c.sma9 != null).map(c => ({
            time: c.time as UTCTimestamp, value: c.sma9!,
          })));
          sma21Series?.setData(candles.filter(c => c.sma21 != null).map(c => ({
            time: c.time as UTCTimestamp, value: c.sma21!,
          })));
          vwapSeries.forEach(segment => chart?.removeSeries(segment));
          vwapSeries = [];
          vwapBySession.clear();
          // Whitespace points alone do not break line series in this library.
          // Independent series guarantee no diagonal across sessions; REST only.
          const segments = setup91Only ? [] : vwapSegments(candles);
          vwapSeries = segments.map(({ session, points }) => {
            const latestSession = session === candles.at(-1)?.vwapSession;
            const segment = chart!.addSeries(LineSeries, {
              color: theme.palette.warning.main, lineWidth: 2, priceScaleId: 'right', title: 'VWAP',
              priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
              lastValueVisible: latestSession, priceLineVisible: latestSession,
            });
            segment.setData(points.map(point => ({ ...point, time: point.time as UTCTimestamp })));
            return segment;
          });
          showSma(candles.at(-1));
          candles.forEach(updateVwap);
          if (!initialized && candles.length) {
            initializeViewport();
          }
        },
        updateLast(candle) {
          // Same raw candle timestamp: updates the last bar, never appends a synthetic one.
          series.update(toBar(candle));
          initializeViewport();
          if (candle.sma9 != null) smaSeries?.update({ time: candle.time as UTCTimestamp, value: candle.sma9 });
          if (candle.sma21 != null) sma21Series?.update({ time: candle.time as UTCTimestamp, value: candle.sma21 });
          updateVwap(candle);
          showSma(candle);
        },
        addReplaySetupMarker,
        addEma9Reversal,
        addReplayTradeMarker,
      });
    } catch {
      setFailed(true);
    }
    return () => { detach?.(); chart?.remove(); };
  }, [controller, theme, setup91Only]);

  return (
    <>
      {!setup91Only && <>
      <Box sx={{ color: 'success.main', fontSize: '0.875rem', mb: 1 }}>
        MM9: <span ref={smaLabel}>&mdash;</span>
      </Box>
      <Box sx={{ color: '#ff5252', fontSize: '0.875rem', mb: 1 }}>
        MM21: <span ref={sma21Label}>&mdash;</span>
      </Box>
      <Box sx={{ color: 'warning.main', fontSize: '0.875rem', mb: 1 }}>
        VWAP: <span ref={vwapLabel}>&mdash;</span>
        <Box component="span" sx={{ color: 'text.secondary', ml: 1, fontSize: '0.75rem' }}>
          HLC3 / volume real &middot; causal no Replay
        </Box>
      </Box>
      </>}
      {failed ? <Alert severity="error">Não foi possível desenhar o gráfico de candles.</Alert> : null}
      <Box ref={container} role="img" aria-label="Candlesticks WINV26 M5, horários em UTC"
        sx={{ height: 480, width: '100%', minWidth: 0 }} />
      <Box sx={{ color: 'text.secondary', fontSize: '0.75rem', mt: 1 }}>
        MME9: seta verde = inversão para alta; seta vermelha = inversão para baixa.
        Setas após dois candles fechados na nova direção da MME9, com deslocamento maior que 15 pontos.
        O fechamento também deve avançar mais de 15 pontos no sentido da seta. Valores em UTC.
      </Box>
      <Box component="pre" ref={setupDetails} aria-label="Setup91 event details"
        sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: '0.75rem', m: 0, mt: 1 }} />
    </>
  );
}
