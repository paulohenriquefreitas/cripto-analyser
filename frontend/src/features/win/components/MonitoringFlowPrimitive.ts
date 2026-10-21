import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp } from 'lightweight-charts';
import type { SwingLevelMonitoring } from '../models/swingLevelMonitoring';
import type { MonitoringFlowWindow } from '../models/monitoringFlow';

const n = (value: number | null) => value == null ? '--' : value.toLocaleString('pt-BR', { maximumFractionDigits: 2 });
export function flowLines(state: ReturnType<SwingLevelMonitoring['getSnapshot']>): string[] {
  const levels = state.levels.filter(level => level.state === 'MONITORING');
  if (!levels.length) return [];
  const windowText = (window: MonitoringFlowWindow) =>
    `${window.seconds}s | C ${n(window.buyVolume)} (${n(window.buyPercent)}%) | V ${n(window.sellVolume)} (${n(window.sellPercent)}%)`
    + ` | UNKNOWN ${n(window.unknownVolume)} (${n(window.unknownPercent)}%) | Delta ${n(window.delta)}`
    + ` | LAST ${n(window.lastDisplacement)} pts | ${window.quality}`
    + (window.quality === 'GOOD' && window.alert ? ` | ${window.alert}` : '');
  const lines = ['Times & Trades compartilhado | volume real MT5 | % sobre volume total'];
  if (state.flow) lines.push(...state.flow.windows.map(windowText));
  else lines.push('Fluxo indisponível | aguardando Times & Trades');
  for (const level of levels) {
    lines.push(`Pré-aproximação ${level.type === 'TOP' ? 'Topo' : 'Fundo'} ${n(level.price)}:`);
    if (level.baseline) {
      lines.push(level.baseline.windows.map(window => `${window.seconds}s: Delta ${n(window.delta)}, LAST ${n(window.lastDisplacement)} pts, UNKNOWN ${n(window.unknownPercent)}%, ${window.quality}`).join(' | '));
    } else lines.push('Sem amostra anterior à ativação.');
  }
  return lines;
}

/** A shared metrics panel anchored above the current candle, removed outside MONITORING. */
export class MonitoringFlowPrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private time?: UTCTimestamp;
  private high?: number;
  private lines: string[] = [];
  private background: string;
  private color: string;
  constructor(background: string, color: string) { this.background = background; this.color = color; }
  private readonly renderer: IPrimitivePaneRenderer = {
    draw: target => {
      if (!this.attachment || this.time === undefined || this.high === undefined || !this.lines.length) return;
      const candleX = this.attachment.chart.timeScale().timeToCoordinate(this.time);
      const candleY = this.attachment.series.priceToCoordinate(this.high);
      if (candleX == null || candleY == null) return;
      target.useMediaCoordinateSpace(({ context, mediaSize }) => {
        if (candleX < 0 || candleX > mediaSize.width) return;
        context.save();
        context.font = '11px sans-serif';
        const width = Math.min(760, mediaSize.width - 16);
        if (width < 40) { context.restore(); return; }
        const wrapped: string[] = [];
        for (const line of this.lines) {
          let current = '';
          for (const word of line.split(' ')) {
            const candidate = current ? `${current} ${word}` : word;
            if (current && context.measureText(candidate).width > width - 16) {
              wrapped.push(current); current = word;
            } else current = candidate;
          }
          wrapped.push(current);
        }
        const height = wrapped.length * 15 + 16;
        const x = Math.max(8, Math.min(candleX - width / 2, mediaSize.width - width - 8));
        const y = Math.max(4, candleY - height - 35);
        context.fillStyle = this.background;
        context.globalAlpha = 0.95;
        context.fillRect(x, y, width, height);
        context.globalAlpha = 1;
        context.fillStyle = this.color;
        context.textBaseline = 'top';
        wrapped.forEach((line, index) => context.fillText(line, x + 8, y + 8 + index * 15));
        context.restore();
      });
    },
  };
  private readonly view = { zOrder: () => 'top' as const, renderer: () => this.renderer };
  attached(attachment: SeriesAttachedParameter<Time>) { this.attachment = attachment; }
  detached() { this.attachment = undefined; }
  paneViews() { return [this.view]; }
  setSnapshot(time: UTCTimestamp | undefined, high: number | undefined, state: ReturnType<SwingLevelMonitoring['getSnapshot']>) {
    this.time = time;
    this.high = high;
    this.lines = flowLines(state);
    this.attachment?.requestUpdate();
  }
}
