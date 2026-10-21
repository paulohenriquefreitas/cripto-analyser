import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp, Logical } from 'lightweight-charts';
import type { PlacedAbsorption } from '../../replay/models/absorptionReplay';

/** Paints at the recorded PRICE and capture interval, not above/below candle OHLC. */
export class AbsorptionPrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private markers: PlacedAbsorption[] = [];
  private hits: { id: string; x: number; y: number }[] = [];
  private selected?: string;
  private readonly renderer: IPrimitivePaneRenderer = {
    draw: target => {
      this.hits = [];
      if (!this.attachment) return;
      const { chart, series } = this.attachment;
      target.useMediaCoordinateSpace(({ context: ctx }) => {
        for (const m of this.markers) {
          const left = chart.timeScale().timeToCoordinate(m.candleTime as UTCTimestamp);
          const next = chart.timeScale().logicalToCoordinate((m.candleIndex + 1) as Logical);
          const y = series.priceToCoordinate(m.price);
          if (left == null || next == null || y == null) continue;
          const x = left + (next - left)*m.fraction;
          this.hits.push({ id: m.id, x, y });
          ctx.save();
          ctx.strokeStyle = m.color; ctx.fillStyle = m.color; ctx.lineWidth = 2;
          // The translucent band represents available UTC resolution, not per-tick precision.
          ctx.globalAlpha = .2;
          ctx.fillRect(x, y - 12, Math.max(1, (next - left)*m.widthFraction), 24);
          ctx.globalAlpha = 1;
          ctx.beginPath();
          if (m.shape === 'bubble') { ctx.arc(x, y, 7, 0, Math.PI*2); ctx.fill(); }
          else if (m.shape === 'cross') { ctx.moveTo(x-6,y-6);ctx.lineTo(x+6,y+6);ctx.moveTo(x+6,y-6);ctx.lineTo(x-6,y+6);ctx.stroke(); }
          else if (m.shape === 'diamond') { ctx.moveTo(x,y-7);ctx.lineTo(x+6,y);ctx.lineTo(x,y+7);ctx.lineTo(x-6,y);ctx.closePath();ctx.stroke(); }
          else { const d = m.shape === 'up' ? -1 : 1; ctx.moveTo(x,y+7*d);ctx.lineTo(x,y-7*d);ctx.moveTo(x-5,y+2*d);ctx.lineTo(x,y+7*d);ctx.lineTo(x+5,y+2*d);ctx.stroke(); }
          if (m.id === this.selected) { ctx.strokeStyle = '#ffffff';ctx.beginPath();ctx.arc(x,y,11,0,Math.PI*2);ctx.stroke(); }
          ctx.restore();
        }
      });
    },
  };
  private readonly view = { zOrder: () => 'top' as const, renderer: () => this.renderer };
  attached(value: SeriesAttachedParameter<Time>) { this.attachment = value; }
  detached() { this.attachment = undefined; this.hits = []; }
  paneViews() { return [this.view]; }
  autoscaleInfo(start: Logical, end: Logical) {
    const prices = this.markers.filter(m => m.candleIndex + m.fraction >= start && m.candleIndex + m.fraction <= end).map(m => m.price);
    return prices.length ? { priceRange: { minValue: Math.min(...prices), maxValue: Math.max(...prices) } } : null;
  }
  setMarkers(markers: PlacedAbsorption[], selected?: string) { this.markers = markers; this.selected = selected; this.attachment?.requestUpdate(); }
  pick(x: number, y: number) { return [...this.hits].reverse().find(p => Math.hypot(p.x-x, p.y-y) <= 12)?.id; }
}
