import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp, Logical } from 'lightweight-charts';
import type { MicroDowFiftySnapshot } from '../api/threeCandlesApi';

export type MicroFiftyOverlay = { snapshot?: MicroDowFiftySnapshot | null; visible: boolean; principalLevel?: number };

/** Cyan dotted midpoint of the current confirmed micro wave. Labels move; prices never do. */
export class MicroDowFiftyPrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private overlay: MicroFiftyOverlay = {visible:false};
  private times: readonly number[] = [];
  private coordinate(time:number) {
    const scale=this.attachment!.chart.timeScale(),direct=scale.timeToCoordinate(time as UTCTimestamp);
    if(direct!=null)return direct;
    const last=this.times.at(-1);if(last==null)return null;
    let i=0;while(i<this.times.length&&this.times[i]<time)i++;
    const logical=i===0?(time-this.times[0])/300:i===this.times.length?this.times.length-1+(time-last)/300
      :i-1+(time-this.times[i-1])/(this.times[i]-this.times[i-1]);
    return scale.logicalToCoordinate(logical as Logical);
  }
  private readonly renderer:IPrimitivePaneRenderer={draw:target=>{
    const s=this.overlay.snapshot,a=this.attachment;
    if(!this.overlay.visible||!s||!a)return;
    const ref=s.references.find(r=>r.wave.id===s.currentWaveId);
    if(!ref || ref.supersededAtMsc!=null || ref.wave.availableAtMsc>s.availableAtMsc)return;
    const w=ref.wave;
    if(w.initial.confirmedAtMsc>s.availableAtMsc || w.terminal.confirmedAtMsc>s.availableAtMsc)return;
    const y=a.series.priceToCoordinate(w.level50),from=this.coordinate(w.initial.extremeTimeMsc/1000),to=this.coordinate(w.terminal.extremeTimeMsc/1000);
    if(y==null||from==null||to==null)return;
    target.useMediaCoordinateSpace(({context:ctx,mediaSize})=>{
      if(y<0||y>mediaSize.height)return;
      const x=Math.max(0,(from+to)/2),end=mediaSize.width-2;if(x>end)return;
      ctx.save();ctx.strokeStyle='#06B6D4';ctx.fillStyle='#06B6D4';ctx.lineWidth=1;ctx.setLineDash([2,4]);
      ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(end,y);ctx.stroke();ctx.font='12px sans-serif';
      const text='DOW MICRO 50% — '+w.level50.toLocaleString('pt-BR',{maximumFractionDigits:2});
      const principalY=this.overlay.principalLevel==null?null:a.series.priceToCoordinate(this.overlay.principalLevel);
      // Principal label uses y-7. Place micro below both labels when nearby; near the bottom use above.
      let labelY=y+17;
      if(principalY!=null && Math.abs(y-principalY)<30) labelY=Math.max(labelY,principalY+19);
      if(labelY>mediaSize.height-3)labelY=Math.min(y-29,principalY==null?y-29:principalY-29);
      labelY=Math.max(13,labelY);
      ctx.fillText(text,Math.max(4,Math.min(x+6,mediaSize.width-ctx.measureText(text).width-6)),labelY);
      ctx.restore();
    });
  }};
  private readonly view={zOrder:()=> 'top' as const,renderer:()=>this.renderer};
  paneViews(){return [this.view];}
  attached(a:SeriesAttachedParameter<Time>){this.attachment=a;}
  detached(){this.attachment=undefined;}
  updateAllViews(){}
  set(overlay:MicroFiftyOverlay,times:readonly number[]=[]){this.overlay=overlay;this.times=times;this.attachment?.requestUpdate();}
}
