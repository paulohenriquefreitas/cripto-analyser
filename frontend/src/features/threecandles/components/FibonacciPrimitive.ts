import type { ISeriesPrimitive, IPrimitivePaneRenderer, ISeriesPrimitiveAxisView, SeriesAttachedParameter, Time, UTCTimestamp } from 'lightweight-charts';
import type { FibAnchor, FibVersion } from '../models/FibonacciMovementEngine';

export type FibonacciOverlay = { primary?: FibVersion; comparison?: FibVersion; anchors: readonly FibAnchor[]; pick?: 'TOP' | 'BOTTOM' };
const price = (n: number) => n.toLocaleString('pt-BR', { maximumFractionDigits: 2 });

/** No autoscaleInfo: overlays and labels never expand the candle price scale. */
export class FibonacciPrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private overlay: FibonacciOverlay = { anchors: [] };
  private times: readonly number[] = [];
  private labels: ISeriesPrimitiveAxisView[] = [];
  private readonly foreground: string;
  private readonly background: string;
  constructor(foreground: string, background: string) { this.foreground=foreground; this.background=background; }
  private x(time: number): number | null {
    const a = this.attachment;
    if (!a || !this.times.length) return null;
    const exact = a.chart.timeScale().timeToCoordinate(time as UTCTimestamp);
    if (exact != null) return exact;
    if (time < this.times[0]) return 0;
    const last = this.times[this.times.length - 1];
    if (time > last) {
      const x = a.chart.timeScale().timeToCoordinate(last as UTCTimestamp);
      return x == null ? null : x + a.chart.timeScale().options().barSpacing;
    }
    const next = this.times.find(t => t >= time);
    return next == null ? null : a.chart.timeScale().timeToCoordinate(next as UTCTimestamp);
  }
  private points(v: FibVersion) {
    const a = this.attachment;
    if (!a) return;
    const first = v.high.extremeTimeMsc < v.low.extremeTimeMsc ? v.high : v.low;
    const second = first === v.high ? v.low : v.high;
    const ax = a.chart.timeScale().timeToCoordinate(first.extremeTimeMsc / 1000 as UTCTimestamp);
    const bx = a.chart.timeScale().timeToCoordinate(second.extremeTimeMsc / 1000 as UTCTimestamp);
    const ay = a.series.priceToCoordinate(first.price), by = a.series.priceToCoordinate(second.price);
    if (ax == null || bx == null || ay == null || by == null) return;
    return { ax,ay,bx,by };
  }
  private readonly renderer: IPrimitivePaneRenderer = { draw: target => {
    const a = this.attachment;
    if (!a) return;
    target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
      ctx.save();
      for (const [v, comparison] of [[this.overlay.comparison, true], [this.overlay.primary, false]] as const) {
        if (!v) continue;
        // Origins are the actual anchor candles, not the later decision/confirmation candle.
        // Do not snap an absent origin to another candle or to the viewport edge.
        const points = this.points(v);
        if (!points) continue;
        const {ax,ay,bx,by} = points;
        const left = Math.max(0,Math.min(ax,bx));
        if (left >= mediaSize.width) continue;
        const top = Math.max(0,Math.min(ay,by));
        const bottom = Math.min(mediaSize.height,Math.max(ay,by));
        // Right-extending amplitude band. Canvas clipping preserves offscreen anchors.
        if (bottom > top) {
          ctx.fillStyle = comparison ? '#38BDF8' : '#2563EB';
          ctx.globalAlpha = comparison ? 0.04 : 0.1;
          ctx.fillRect(left,top,mediaSize.width-left,bottom-top);
        }
        const color = comparison ? '#38BDF8' : this.foreground;
        // Profit-reference orientation is price-based: 0% high, 100% low in both directions.
        for (const [p, percent] of [[v.high.price,0],[v.fib50,50],[v.low.price,100]] as const) {
          const y = a.series.priceToCoordinate(p);
          if (y == null || y < 0 || y > mediaSize.height) continue;
          ctx.strokeStyle = color;
          ctx.globalAlpha = percent === 50 ? (comparison ? 0.7 : 1) : 0.65;
          ctx.lineWidth = percent === 50 && !comparison ? 2.5 : 1;
          ctx.setLineDash(comparison ? [5,4] : []);
          ctx.beginPath(); ctx.moveTo(left,y); ctx.lineTo(mediaSize.width,y); ctx.stroke();
        }
        // Exact A -> B segment, independently of the order high/low were supplied.
        ctx.globalAlpha = comparison ? 0.6 : 0.9; ctx.strokeStyle = color;
        ctx.lineWidth = 1.2; ctx.setLineDash([2,4]);
        ctx.beginPath(); ctx.moveTo(ax,ay); ctx.lineTo(bx,by); ctx.stroke();
      }
      if (this.overlay.pick) for (const p of this.overlay.anchors.filter(p => p.type === this.overlay.pick)) {
        const x = this.x(p.extremeTimeMsc / 1000), y = a.series.priceToCoordinate(p.price);
        if (x == null || y == null) continue;
        ctx.globalAlpha = 1; ctx.strokeStyle = '#38BDF8'; ctx.lineWidth = 2; ctx.setLineDash([]);
        ctx.beginPath(); ctx.arc(x,y,5,0,Math.PI*2); ctx.stroke();
      }
      ctx.restore();
    });
  } };
  // Text/handles above candles remain readable; the blue band and levels stay behind them.
  private readonly labelRenderer: IPrimitivePaneRenderer = { draw: target => {
    const a = this.attachment;
    if (!a) return;
    target.useMediaCoordinateSpace(({context:ctx,mediaSize}) => {
      ctx.save(); ctx.setLineDash([]); ctx.textAlign='left';
      for (const [v,comparison] of [[this.overlay.comparison,true],[this.overlay.primary,false]] as const) {
        if (!v) continue;
        const points=this.points(v);
        if (!points) continue;
        const {ax,ay,bx,by}=points, left=Math.max(0,Math.min(ax,bx));
        if (left>=mediaSize.width) continue;
        const color=comparison?'#38BDF8':this.foreground;
        for (const [p,percent] of [[v.high.price,0],[v.fib50,50],[v.low.price,100]] as const) {
          const y=a.series.priceToCoordinate(p);
          if (y==null||y<0||y>mediaSize.height) continue;
          const label=`${comparison?'COMP ':''}${percent}% — ${price(p)}`;
          ctx.font=`${percent===50?'600 ':''}12px sans-serif`; ctx.textBaseline='bottom';
          const right=mediaSize.width-ctx.measureText(label).width-4;
          const x=Math.max(4,comparison?right:Math.min(left+14,right));
          ctx.globalAlpha=0.95;ctx.strokeStyle=this.background;ctx.lineWidth=3;
          ctx.strokeText(label,x,Math.max(16,y-4));ctx.fillStyle=color;ctx.fillText(label,x,Math.max(16,y-4));
        }
        for (const [x,y,label] of [[ax,ay,'A'],[bx,by,'B']] as const) {
          if(x<0||x>mediaSize.width||y<0||y>mediaSize.height)continue;
          ctx.globalAlpha=1;ctx.fillStyle=color;ctx.beginPath();ctx.arc(x,y,3.5,0,Math.PI*2);ctx.fill();
          ctx.font='600 11px sans-serif';ctx.textBaseline='top';ctx.strokeStyle=this.background;ctx.lineWidth=3;
          ctx.strokeText(label,x+6,Math.min(mediaSize.height-14,y+7));ctx.fillText(label,x+6,Math.min(mediaSize.height-14,y+7));
        }
      }
      ctx.restore();
    });
  } };
  private readonly view = { zOrder: () => 'bottom' as const, renderer: () => this.renderer };
  private readonly labelView = { zOrder: () => 'top' as const, renderer: () => this.labelRenderer };
  attached(a: SeriesAttachedParameter<Time>) { this.attachment = a; }
  detached() { this.attachment = undefined; }
  paneViews() { return [this.view,this.labelView]; }
  priceAxisViews() { return this.labels; }
  set(overlay: FibonacciOverlay, times: readonly number[]) {
    this.overlay = overlay; this.times = times;
    this.labels = ([[overlay.primary,false],[overlay.comparison,true]] as const).flatMap(([v,comparison]) => v ? [{
      coordinate: () => this.attachment?.series.priceToCoordinate(v.fib50) ?? -10000,
      text: () => `${comparison ? 'COMPARAR' : 'FIBO'} 50% — ${price(v.fib50)}`,
      textColor: () => comparison ? '#38BDF8' : this.foreground, backColor: () => this.background,
      visible: () => { const y = this.attachment?.series.priceToCoordinate(v.fib50); return !!this.points(v) && y != null && y >= 0 && y <= (this.attachment?.chart.paneSize().height ?? 0); },
      tickVisible: () => true,
    }] : []);
    this.attachment?.requestUpdate();
  }
  pick(x: number, y: number): FibAnchor | undefined {
    return this.overlay.anchors.filter(a => a.type === this.overlay.pick).map(anchor => {
      const px = this.x(anchor.extremeTimeMsc / 1000), py = this.attachment?.series.priceToCoordinate(anchor.price);
      return { anchor, distance: px == null || py == null ? Infinity : Math.hypot(px-x,py-y) };
    }).filter(p => p.distance <= 24).sort((a,b) => a.distance-b.distance || a.anchor.id.localeCompare(b.anchor.id))[0]?.anchor;
  }
}
