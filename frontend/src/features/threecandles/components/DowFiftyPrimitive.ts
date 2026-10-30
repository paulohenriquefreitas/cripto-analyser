import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp, Logical } from 'lightweight-charts';
import type { DowFiftySnapshot } from '../api/threeCandlesApi';
export type DowFiftyOverlay = {snapshot?:DowFiftySnapshot|null;visible:boolean};

/** One current midpoint only; history never becomes active through chart selection. No autoscale contribution. */
export class DowFiftyPrimitive implements ISeriesPrimitive<Time> {
  private attachment?:SeriesAttachedParameter<Time>;
  private overlay:DowFiftyOverlay={visible:false};
  private times:readonly number[]=[];
  private coordinate(time:number){
    const scale=this.attachment!.chart.timeScale(),direct=scale.timeToCoordinate(time as UTCTimestamp);
    if(direct!=null)return direct;
    const last=this.times.at(-1);if(last==null)return null;
    let index=0;while(index<this.times.length&&this.times[index]<time)index++;
    const logical=index===0?(time-this.times[0])/300:index===this.times.length
      ? this.times.length-1+(time-last)/300
      : index-1+(time-this.times[index-1])/(this.times[index]-this.times[index-1]);
    return scale.logicalToCoordinate(logical as Logical);
  }
  private geometry?:{x:number;end:number;y:number;id:string};
  private readonly renderer:IPrimitivePaneRenderer={draw:target=>{
    this.geometry=undefined;
    const s=this.overlay.snapshot;if(!this.overlay.visible||!s||!this.attachment)return;
    const forming=s.formingWave;
    const ref=s.references.find(r=>r.wave.id===s.currentWaveId);
    if(forming ? forming.availableAtMsc>s.availableAtMsc||forming.recognizedAtMsc>s.availableAtMsc
      : !ref||ref.state==='ONDA_ENCERRADA'||ref.endedAtMsc!=null||ref.wave.recognizedAtMsc>s.availableAtMsc)return;
    const wave=forming??ref!.wave;
    const a=this.attachment,level=wave.level50;
    const y=a.series.priceToCoordinate(level);if(y==null)return;
    // Geometric origin belongs to the wave. Visibility still begins only at recognition.
    const initialX=this.coordinate(wave.initial.extremeTimeMsc/1000);
    const terminalX=this.coordinate((forming?forming.extremeTimeMsc:ref!.wave.terminal.extremeTimeMsc)/1000);
    if(initialX==null||terminalX==null)return;
    const origin=(initialX+terminalX)/2;
    target.useMediaCoordinateSpace(({context:ctx,mediaSize})=>{
      const x=Math.max(0,origin),end=mediaSize.width-2;if(x>end)return;
      if(!forming)this.geometry={x,end,y,id:wave.id};
      ctx.save();ctx.strokeStyle='#FFFFFF';ctx.fillStyle='#FFFFFF';ctx.lineWidth=2.5;ctx.setLineDash([]);
      if(forming) {
        const startY=a.series.priceToCoordinate(forming.initial.price);
        const endY=a.series.priceToCoordinate(forming.direction==='UP'?forming.high:forming.low);
        if(startY!=null&&endY!=null){
          ctx.lineWidth=1;ctx.setLineDash([4,3]);ctx.beginPath();ctx.moveTo(initialX,startY);ctx.lineTo(terminalX,endY);ctx.stroke();
          ctx.lineWidth=2.5;ctx.setLineDash([]);
        }
      }
      ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(end,y);ctx.stroke();ctx.font='12px sans-serif';
      ctx.fillText('DOW 50% \u2014 '+level.toLocaleString('pt-BR',{maximumFractionDigits:2})+(forming?' (em forma\u00e7\u00e3o)':''),Math.max(4,Math.min(x+6,mediaSize.width-(forming?310:190))),y-7);
      ctx.restore();
    });
  }};
  private readonly view={zOrder:()=> 'top' as const,renderer:()=>this.renderer};
  paneViews(){return [this.view];} attached(a:SeriesAttachedParameter<Time>){this.attachment=a;}
  detached(){this.attachment=undefined;this.geometry=undefined;} updateAllViews(){}
  set(overlay:DowFiftyOverlay,times:readonly number[]=[]){this.overlay=overlay;this.times=times;this.geometry=undefined;this.attachment?.requestUpdate();}
  pick(x:number,y:number):string|undefined {const g=this.geometry;if(this.overlay.visible&&g&&Math.abs(y-g.y)<=8&&x>=g.x-6&&x<=g.end+6)return g.id;}
}
