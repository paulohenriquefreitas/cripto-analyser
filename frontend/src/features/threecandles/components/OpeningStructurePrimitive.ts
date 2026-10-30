import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp } from 'lightweight-charts';
import type { OpeningStructureSnapshot } from '../api/threeCandlesApi';

export type OpeningOverlay = { snapshot?: OpeningStructureSnapshot | null; visible: boolean; minimumPoints: number };

/** Closed-bar opening hypothesis only. No pivot markers, Fibonacci or strategy inputs. */
export class OpeningStructurePrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private overlay: OpeningOverlay = { visible: false, minimumPoints: 0 };
  private readonly renderer: IPrimitivePaneRenderer = { draw: target => {
    const { snapshot: s, visible, minimumPoints } = this.overlay, a = this.attachment;
    if (!s || !visible || !a) return;
    target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
      ctx.save(); ctx.font = '11px sans-serif'; ctx.lineWidth = 2;
      const point = (r: { formationTimeMsc: number; availableAtMsc: number; price: number }) => {
        if (r.availableAtMsc > s.availableAtMsc) return;
        const x = a.chart.timeScale().timeToCoordinate(r.formationTimeMsc / 1000 as UTCTimestamp), y = a.series.priceToCoordinate(r.price);
        return x == null || y == null ? undefined : { x, y };
      };
      const leg = s.initialLeg;
      if (leg && leg.availableAtMsc <= s.availableAtMsc && leg.amplitudePoints >= minimumPoints) {
        const from = point(leg.origin), to = point(leg.destination);
        // The first M5's high/low order is unknown: never draw an invented intrabar path.
        if (from && to && leg.intrabarOrder === 'ORDERED_M5_BARS') {
          ctx.strokeStyle = '#FB923C'; ctx.fillStyle = '#FB923C'; ctx.setLineDash([9, 5]);
          ctx.beginPath(); ctx.moveTo(from.x, from.y); ctx.lineTo(to.x, to.y); ctx.stroke();
          const label = `Abertura EM FORMAÇÃO · ${leg.amplitudePoints.toLocaleString('pt-BR')} pts`;
          ctx.fillText(label, Math.max(4, Math.min(to.x + 8, mediaSize.width - ctx.measureText(label).width - 4)), to.y - 12);
        }
      }
      const gap = s.gap;
      if (gap && gap.availableAtMsc <= s.availableAtMsc && gap.points !== 0) {
        // Vertical discontinuity at the open, never a traded cross-session wave.
        const to = point(gap.to), y = a.series.priceToCoordinate(gap.from.price);
        if (to && y != null) {
          ctx.strokeStyle = '#A78BFA'; ctx.fillStyle = '#A78BFA'; ctx.setLineDash([2, 5]);
          ctx.beginPath(); ctx.moveTo(to.x - 4, y); ctx.lineTo(to.x - 4, to.y); ctx.stroke();
          ctx.fillText(`GAP ${gap.points > 0 ? '+' : ''}${gap.points.toLocaleString('pt-BR')} pts`, to.x + 8, to.y - 12);
        }
      }
      ctx.restore();
    });
  } };
  private readonly view = { zOrder: () => 'top' as const, renderer: () => this.renderer };
  paneViews() { return [this.view]; }
  attached(a: SeriesAttachedParameter<Time>) { this.attachment = a; }
  detached() { this.attachment = undefined; }
  updateAllViews() {}
  set(overlay: OpeningOverlay) { this.overlay = overlay; this.attachment?.requestUpdate(); }
}
