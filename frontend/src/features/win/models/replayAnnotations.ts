import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, SeriesMarker, UTCTimestamp } from 'lightweight-charts';
import type { ReplayTradeMarker } from '@/features/replay/api/replayTradeStore';

type Label = { x: number; y: number; width: number; height: number; text: string; color: string; anchorX: number; anchorY: number };

// Screen-space layout is recomputed on every paint, including zoom, pan and resize.
export function placeReplayLabels(labels: Label[], width: number, height: number): Label[] {
  const placed: Label[] = [];
  for (const label of labels) {
    const next = { ...label, x: Math.max(2, Math.min(width - label.width - 2, label.x)),
      y: Math.max(2, Math.min(height - label.height - 2, label.y)) };
    const overlaps = (y: number) => placed.some(p => next.x < p.x + p.width + 4
      && next.x + next.width + 4 > p.x && y < p.y + p.height + 4 && y + next.height + 4 > p.y);
    for (let step = 0; overlaps(next.y) && step < Math.ceil(height / (next.height + 4)); step++) {
      const candidates = [label.y - (step + 1) * (next.height + 4), label.y + (step + 1) * (next.height + 4)];
      const free = candidates.find(y => y >= 2 && y + next.height <= height - 2 && !overlaps(y));
      if (free != null) { next.y = free; break; }
    }
    placed.push(next);
  }
  return placed;
}

export class ReplayAnnotations implements ISeriesPrimitive {
  private attachedTo?: SeriesAttachedParameter;
  constructor(private markers: Map<string, SeriesMarker<UTCTimestamp>>,
    private trades: Map<string, ReplayTradeMarker>, private background: string) {}
  attached(param: SeriesAttachedParameter) { this.attachedTo = param; }
  detached() { this.attachedTo = undefined; }
  refresh() { this.attachedTo?.requestUpdate(); }
  paneViews() { return [{ zOrder: () => 'top' as const, renderer: () => this.renderer }]; }
  private renderer: IPrimitivePaneRenderer = {
    draw: target => target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
      const api = this.attachedTo;
      if (!api) return;
      const point = (time: number, price: number) => {
        const x = api.chart.timeScale().timeToCoordinate(time as UTCTimestamp);
        const y = api.series.priceToCoordinate(price);
        return x == null || y == null ? null : { x: Number(x), y: Number(y) };
      };
      ctx.save();
      ctx.font = '11px sans-serif';
      const entries = new Map([...this.trades.values()].filter(t => t.stage === 'ENTRY').map(t => [t.tradeId, t]));
      for (const exit of this.trades.values()) {
        if (exit.stage !== 'EXIT') continue;
        const entry = entries.get(exit.tradeId);
        if (!entry) continue;
        const a = point(entry.time, entry.price), b = point(exit.time, exit.price);
        if (!a || !b) continue;
        ctx.strokeStyle = (exit.points ?? 0) > 0 ? '#2e7d32' : '#888888';
        ctx.globalAlpha = 0.65;
        ctx.setLineDash([3, 4]);
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
        ctx.setLineDash([]); ctx.globalAlpha = 1;
        ctx.fillStyle = ctx.strokeStyle;
        ctx.beginPath(); ctx.arc(b.x, b.y, 3, 0, Math.PI * 2); ctx.fill();
      }
      const labels: Label[] = [];
      for (const marker of [...this.markers.values()].sort((a, b) => Number(a.time) - Number(b.time))) {
        if (!marker.text) continue;
        const trade = this.trades.get(marker.id!);
        if (trade?.stage === 'REJECTED' || trade?.stage === 'ENTRY') continue;
        const index = api.chart.timeScale().timeToIndex(marker.time, false);
        const bar = index == null ? null : api.series.dataByIndex(index);
        const price = trade?.price ?? (bar && 'high' in bar && 'low' in bar && typeof bar.high === 'number' && typeof bar.low === 'number' ? (marker.position === 'belowBar' ? bar.low : bar.high) : undefined);
        if (price == null) continue;
        const p = point(Number(marker.time), price);
        if (!p || p.x < 0 || p.x > mediaSize.width) continue;
        const width = ctx.measureText(marker.text).width + 10;
        labels.push({ x: p.x - width / 2, y: p.y + (marker.position === 'belowBar' ? 16 : -34),
          width, height: 18, text: marker.text, color: marker.color, anchorX: p.x, anchorY: p.y });
      }
      for (const label of placeReplayLabels(labels, mediaSize.width, mediaSize.height)) {
        ctx.strokeStyle = label.color; ctx.globalAlpha = 0.45;
        ctx.beginPath(); ctx.moveTo(label.anchorX, label.anchorY);
        ctx.lineTo(label.x + label.width / 2, label.y + label.height / 2); ctx.stroke();
        ctx.globalAlpha = 1; ctx.fillStyle = this.background;
        ctx.fillRect(label.x, label.y, label.width, label.height);
        ctx.fillStyle = label.color; ctx.textBaseline = 'middle';
        ctx.fillText(label.text, label.x + 5, label.y + label.height / 2);
      }
      ctx.restore();
    }),
  };
}
