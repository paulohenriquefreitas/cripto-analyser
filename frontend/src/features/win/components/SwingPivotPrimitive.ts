import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp } from 'lightweight-charts';
import type { SwingPivot } from '../models/mt5SwingPivots';

/** Canvas rays start at each wick, extend right, and follow chart pan/zoom. */
export class SwingPivotPrimitive implements ISeriesPrimitive<Time> {
  private readonly style: { color: string; opacity: number; width: number; dash: number[] };
  constructor(style: Partial<{ color: string; opacity: number; width: number; dash: number[] }> = {}) {
    this.style = { color: '#ff5252', opacity: 0.55, width: 1, dash: [6, 4], ...style };
  }
  private attachment?: SeriesAttachedParameter<Time>;
  private levels: readonly SwingPivot[] = [];
  private firstTime = Infinity;
  private readonly renderer: IPrimitivePaneRenderer = {
    draw: target => {
      const attachment = this.attachment;
      if (!attachment) return;
      target.useMediaCoordinateSpace(({ context, mediaSize }) => {
        context.save();
        context.strokeStyle = this.style.color;
        context.globalAlpha = this.style.opacity;
        context.lineWidth = this.style.width;
        context.setLineDash(this.style.dash);
        for (const level of this.levels) {
          const coordinate = attachment.chart.timeScale().timeToCoordinate(level.time as UTCTimestamp);
          const x = coordinate ?? (level.time < this.firstTime ? 0 : null);
          const y = attachment.series.priceToCoordinate(level.price);
          if (x == null || y == null || x > mediaSize.width || y < 0 || y > mediaSize.height) continue;
          context.beginPath();
          context.moveTo(Math.max(0, x), y);
          context.lineTo(mediaSize.width, y);
          context.stroke();
        }
        context.restore();
      });
    },
  };
  private readonly view = { zOrder: () => 'normal' as const, renderer: () => this.renderer };

  attached(attachment: SeriesAttachedParameter<Time>) { this.attachment = attachment; }
  detached() { this.attachment = undefined; }
  paneViews() { return [this.view]; }
  setLevels(levels: readonly SwingPivot[], firstTime: number) {
    this.levels = levels;
    this.firstTime = firstTime;
    this.attachment?.requestUpdate();
  }
}
