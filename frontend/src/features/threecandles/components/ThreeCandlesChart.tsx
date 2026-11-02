import { ThreeCandlePatternPrimitive } from './ThreeCandlePatternPrimitive';
import type { PatternOverlay } from '../models/threeCandlePatternAudit';
import { MicroDowFiftyPrimitive, type MicroFiftyOverlay } from './MicroDowFiftyPrimitive';
import { OpeningStructurePrimitive, type OpeningOverlay } from './OpeningStructurePrimitive';
import { Box, useTheme } from '@mui/material';
import { useEffect, useRef } from 'react';
import { CandlestickSeries, ColorType, createChart, type UTCTimestamp } from 'lightweight-charts';
import type { ChartSink, StructuralChartLevel } from '@/features/win/models/mt5Intrabar';
import { SwingPivotPrimitive } from '@/features/win/components/SwingPivotPrimitive';
import { FibonacciPrimitive, type FibonacciOverlay } from './FibonacciPrimitive';
import type { FibAnchor } from '../models/FibonacciMovementEngine';
import { DowStructurePrimitive, type DowOverlay } from './DowStructurePrimitive';
import { DowFiftyPrimitive, type DowFiftyOverlay } from './DowFiftyPrimitive';
import type { DowPivot } from '../api/threeCandlesApi';

/** Dedicated chart for this module: shared strategy charts are not modified by Fibonacci. */
export function ThreeCandlesChart({
  controller,
  zone,
  overlay,
  onPick,
  patternOverlay,
  patternViewport,
  dowOverlay,
  microDowOverlay,
  openingOverlay,
  microFiftyOverlay,
  dowFiftyOverlay,
  onPickDowFifty,
  onPickDow,
}: {
  controller: { attachChart: (sink: ChartSink) => () => void };
  zone: string;
  overlay: FibonacciOverlay;
  onPick: (anchor: FibAnchor) => void;
  patternOverlay?: PatternOverlay;
  patternViewport?: {from:number;to:number;request:number};
  dowOverlay?: DowOverlay;
  microDowOverlay?: DowOverlay;
  openingOverlay?: OpeningOverlay;
  microFiftyOverlay?: MicroFiftyOverlay;
  dowFiftyOverlay?: DowFiftyOverlay;
  onPickDowFifty?: (id: string) => void;
  onPickDow?: (pivot: DowPivot) => void;
}) {
  const host = useRef<HTMLDivElement>(null),
    primitive = useRef<FibonacciPrimitive | undefined>(undefined);
  const dowPrimitive = useRef<DowStructurePrimitive | undefined>(undefined);
  const state = useRef({
    overlay,
    onPick,
    patternOverlay,
    dowOverlay,
    microDowOverlay,
    openingOverlay,
    microFiftyOverlay,
    dowFiftyOverlay,
    onPickDowFifty,
    onPickDow,
  });
  state.current = {
    overlay,
    onPick,
    patternOverlay,
    dowOverlay,
    microDowOverlay,
    openingOverlay,
    microFiftyOverlay,
    dowFiftyOverlay,
    onPickDowFifty,
    onPickDow,
  };
  const microPrimitive = useRef<DowStructurePrimitive | undefined>(undefined);
  const openingPrimitive = useRef<OpeningStructurePrimitive | undefined>(undefined);
  const microFiftyPrimitive = useRef<MicroDowFiftyPrimitive | undefined>(undefined);
  const patternPrimitive = useRef<ThreeCandlePatternPrimitive | undefined>(undefined);
  const fiftyPrimitive = useRef<DowFiftyPrimitive | undefined>(undefined);
  const times = useRef<number[]>([]);
  const patternChart=useRef<ReturnType<typeof createChart> | undefined>(undefined);
  const theme = useTheme();
  useEffect(() => {
    if (!host.current) return;
    const chart = createChart(host.current, {
      autoSize: true,
      height: host.current.clientHeight || 560,
      layout: {
        background: { type: ColorType.Solid, color: theme.palette.background.paper },
        textColor: theme.palette.text.secondary,
      },
      grid: {
        vertLines: { visible: false },
        horzLines: { visible: false },
      },
      rightPriceScale: { autoScale: true, scaleMargins: { top: 0.1, bottom: 0.1 } },
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
        barSpacing: 8,
        rightOffset: 5,
        tickMarkFormatter: (time: number) =>
          new Intl.DateTimeFormat('pt-BR', {
            timeZone: zone,
            hour: '2-digit',
            minute: '2-digit',
          }).format(new Date(time * 1000)),
      },
      localization: {
        locale: 'pt-BR',
        timeFormatter: (time: number) =>
          new Intl.DateTimeFormat('pt-BR', {
            timeZone: zone,
            dateStyle: 'short',
            timeStyle: 'short',
          }).format(new Date(time * 1000)),
      },
    });
    patternChart.current=chart;
    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#00FF00',
      downColor: '#FF0000',
      wickUpColor: '#00FF00',
      wickDownColor: '#FF0000',
      borderVisible: false,
    });
    const m5 = new SwingPivotPrimitive({ color: '#EAB308', opacity: 1, width: 1.5, dash: [2, 3] });
    const h1 = new SwingPivotPrimitive({ color: '#FF00FF', opacity: 1, width: 1.5, dash: [2, 3] });
    const fib = new FibonacciPrimitive(
      theme.palette.mode === 'dark' ? '#FFFFFF' : '#111827',
      theme.palette.background.paper,
    );
    primitive.current = fib;
    series.attachPrimitive(m5);
    series.attachPrimitive(h1);
    series.attachPrimitive(fib);
    const dow = new DowStructurePrimitive(theme.palette.mode === 'dark' ? '#E5E7EB' : '#374151');
    dowPrimitive.current = dow;
    series.attachPrimitive(dow);
    const micro = new DowStructurePrimitive('#06B6D4', true);
    microPrimitive.current = micro;
    series.attachPrimitive(micro);
    const fifty = new DowFiftyPrimitive();
    fiftyPrimitive.current = fifty;
    series.attachPrimitive(fifty);
    const opening = new OpeningStructurePrimitive();
    openingPrimitive.current = opening;
    series.attachPrimitive(opening);
    const microFifty = new MicroDowFiftyPrimitive();
    microFiftyPrimitive.current = microFifty;
    series.attachPrimitive(microFifty);
    const pattern = new ThreeCandlePatternPrimitive();
    patternPrimitive.current = pattern;
    series.attachPrimitive(pattern);
    const refresh = () => {
      pattern.set(state.current.patternOverlay ?? { visible: false, numbers: true, highlights: true });
      microFifty.set(state.current.microFiftyOverlay ?? { visible: false }, times.current);
      opening.set(state.current.openingOverlay ?? { visible: false, minimumPoints: 0 });
      fib.set(state.current.overlay, times.current);
      fifty.set(state.current.dowFiftyOverlay ?? { visible: false }, times.current);
      micro.set(state.current.microDowOverlay ?? { visible: false, labels: false });
      dow.set(state.current.dowOverlay ?? { visible: false, labels: true });
    };
    chart.subscribeClick((p) => {
      if (!p.point) return;
      if (state.current.overlay.pick) {
        const anchor = fib.pick(p.point.x, p.point.y);
        if (anchor) state.current.onPick(anchor);
      } else {
        const wave = fifty.pick(p.point.x, p.point.y);
        if (wave) {
          state.current.onPickDowFifty?.(wave);
          return;
        }
        const pivot = dow.pick(p.point.x, p.point.y);
        if (pivot) state.current.onPickDow?.(pivot);
      }
    });
    const bar = (c: { time: number; open: number; high: number; low: number; close: number }) => ({
      ...c,
      time: c.time as UTCTimestamp,
    });
    let initialized = false;
    const detach = controller.attachChart({
      setHistory(candles) {
        times.current = candles.map((c) => c.time);
        series.setData(candles.map(bar));
        if (!candles.length) {
          initialized = false;
          m5.setLevels([], Infinity);
          h1.setLevels([], Infinity);
        } else if (!initialized) {
          chart.timeScale().applyOptions({ barSpacing: 8, rightOffset: 5 });
          chart.timeScale().scrollToPosition(5, false);
          initialized = true;
        }
        refresh();
      },
      updateLast(c) {
        if (times.current.at(-1) !== c.time) times.current.push(c.time);
        series.update(bar(c));
        refresh();
      },
      focusTime() {
        const count = times.current.length;
        if (!count) return;
        chart
          .timeScale()
          .setVisibleLogicalRange({ from: Math.max(0, count - 40), to: Math.max(40, count + 4) });
        chart
          .priceScale('right')
          .applyOptions({ autoScale: true, scaleMargins: { top: 0.1, bottom: 0.1 } });
      },
      setStructureLevels(levels: readonly StructuralChartLevel[]) {
        for (const [tf, p] of [
          [5, m5],
          [60, h1],
        ] as const)
          p.setLevels(
            levels
              .filter((l) => (l.timeframeMinutes ?? 5) === tf)
              .map((l) => ({
                type: l.type === 'TOP' ? 'HIGH' : 'LOW',
                price: l.price,
                time: l.extremeTimeMsc / 1000,
                confirmedAt: l.confirmedAtMsc / 1000,
              })),
            times.current[0] ?? Infinity,
          );
      },
    });
    return () => {
      detach();
      primitive.current = undefined;
      dowPrimitive.current = undefined;
      microPrimitive.current = undefined;
      fiftyPrimitive.current = undefined;
      patternPrimitive.current = undefined;
      microFiftyPrimitive.current = undefined;
      openingPrimitive.current = undefined;
      chart.remove();
      patternChart.current=undefined;
    };
  }, [controller, zone, theme]);
  useEffect(() => {
    primitive.current?.set(overlay, times.current);
  }, [overlay]);
  useEffect(() => {
    dowPrimitive.current?.set(dowOverlay ?? { visible: false, labels: true });
  }, [dowOverlay]);
  useEffect(() => {
    microPrimitive.current?.set(microDowOverlay ?? { visible: false, labels: false });
  }, [microDowOverlay]);
  useEffect(() => {
    fiftyPrimitive.current?.set(dowFiftyOverlay ?? { visible: false }, times.current);
  }, [dowFiftyOverlay]);
  useEffect(() => {
    openingPrimitive.current?.set(openingOverlay ?? { visible: false, minimumPoints: 0 });
  }, [openingOverlay]);
  useEffect(() => {
    microFiftyPrimitive.current?.set(microFiftyOverlay ?? { visible: false }, times.current);
  }, [microFiftyOverlay]);
  useEffect(() => {
    patternPrimitive.current?.set(patternOverlay ?? { visible: false, numbers: true, highlights: true });
  }, [patternOverlay]);
  useEffect(()=>{
    if(!patternViewport||!patternChart.current)return;
    const from=times.current.findIndex(t=>t>=patternViewport.from/1000);
    const last=times.current.findIndex(t=>t>=patternViewport.to/1000);
    if(from>=0)patternChart.current.timeScale().setVisibleLogicalRange({from:from-5,to:Math.max(from,last)+6});
  },[patternViewport]);
  return (
    <Box
      ref={host}
      role="img"
      aria-label="Candles, ondas Dow e níveis de estudo M5/H1 e 50%"
      sx={{ height: { xs: 440, md: 'clamp(500px, 58vh, 760px)' }, width: '100%', minWidth: 0 }}
    />
  );
}
