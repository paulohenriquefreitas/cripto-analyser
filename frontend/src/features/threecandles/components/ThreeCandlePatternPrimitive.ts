import type {ISeriesPrimitive,IPrimitivePaneRenderer,SeriesAttachedParameter,Time,UTCTimestamp} from 'lightweight-charts';
import {knownPatternStages,type PatternOverlay} from '../models/threeCandlePatternAudit';

const colors={1:'#1E40AF',2:'#7DD3FC',3:'#FB923C'} as const;

/** Candle-body outlines and outside-wick badges; never recolors, replaces or autoscales candles. */
export class ThreeCandlePatternPrimitive implements ISeriesPrimitive<Time> {
  private attachment?:SeriesAttachedParameter<Time>;
  private overlay:PatternOverlay={visible:false,numbers:true,highlights:true};
  private readonly renderer:IPrimitivePaneRenderer={draw:target=>{
    const a=this.attachment,o=this.overlay,s=o.snapshot;
    if(!a||!s||!o.visible||(!o.numbers&&!o.highlights))return;
    const bars=new Map((o.candles??[]).filter(b=>b.timeframeMinutes===5&&b.candle.symbol===s.symbol&&b.availableAtMsc<=s.availableAtMsc)
      .map(b=>[b.candle.bucketStartTimeMsc,b.candle]));
    const marks=knownPatternStages(s),scale=a.chart.timeScale();
    const width=Math.max(2,Math.min(30,(scale.options?.().barSpacing??8)*0.7));
    target.useMediaCoordinateSpace(({context:ctx,mediaSize})=>{
      ctx.save();ctx.font='bold 12px sans-serif';ctx.textAlign='center';ctx.setLineDash([]);
      const counts=new Map<string,number>(),outlines=new Map<number,number>();
      const badges:{x:number;y:number;direction:string}[]=[];
      const rendered=new Set<string>();
      for(const mark of marks) {
        const stage=mark.stage,t=stage.observation.candle.bucketStartTimeMsc,c=bars.get(t);
        if(!c)continue;
        const identity=t+':'+mark.direction+':'+stage.number;
        if(rendered.has(identity))continue;rendered.add(identity);
        const x=scale.timeToCoordinate(t/1000 as UTCTimestamp),high=a.series.priceToCoordinate(c.high),low=a.series.priceToCoordinate(c.low);
        const open=a.series.priceToCoordinate(c.open),close=a.series.priceToCoordinate(c.close);
        if(x==null||high==null||low==null||open==null||close==null||x<0||x>mediaSize.width)continue;
        if(o.highlights) {
          const stack=outlines.get(t)??0;outlines.set(t,stack+1);
          const pad=3+stack*2;
          ctx.strokeStyle=colors[stage.number];ctx.lineWidth=2;
          ctx.strokeRect(x-width/2-pad,Math.min(open,close)-pad,width+pad*2,Math.max(2,Math.abs(open-close))+pad*2);
        }
        if(o.numbers) {
          const key=t+':'+mark.direction,stack=counts.get(key)??0;counts.set(key,stack+1);
          let baseline=mark.direction==='BUY'?low+19+stack*22:high-10-stack*22;
          while(badges.some(b=>b.direction===mark.direction&&Math.abs(b.x-x)<20&&Math.abs(b.y-baseline)<20))
            baseline+=mark.direction==='BUY'?22:-22;
          // Native coordinates and clipping: do not clamp an offscreen candle to another price/time.
          if(baseline<10||baseline>mediaSize.height-2)continue;
          badges.push({x,y:baseline,direction:mark.direction});
          ctx.fillStyle=colors[stage.number];ctx.beginPath();ctx.arc(x,baseline-4,9,0,Math.PI*2);ctx.fill();
          ctx.fillStyle=stage.number===2?'#082F49':'#FFFFFF';ctx.fillText(String(stage.number),x,baseline);
        }
      }
      if(o.numbers)for(const entry of o.executions??[]) {
        if(entry.availableAtMsc>s.availableAtMsc)continue;
        const c=bars.get(entry.candleTimeMsc);if(!c)continue;
        const identity=(o.compactExecutions?entry.patternId:entry.candleTimeMsc)+':'+entry.direction+':E';if(rendered.has(identity))continue;rendered.add(identity);
        const x=scale.timeToCoordinate(entry.candleTimeMsc/1000 as UTCTimestamp);
        const edge=a.series.priceToCoordinate(entry.direction==='BUY'?c.low:c.high);
        if(x==null||edge==null||x<0||x>mediaSize.width)continue;
        let y=edge+(entry.direction==='BUY'?19:-10);
        const spacing=o.compactExecutions?14:22;
        while(badges.some(b=>b.direction===entry.direction&&Math.abs(b.x-x)<(o.compactExecutions?13:20)&&Math.abs(b.y-y)<spacing-2))y+=entry.direction==='BUY'?spacing:-spacing;
        if(y<10||y>mediaSize.height-2)continue;
        badges.push({x,y,direction:entry.direction});
        ctx.font=o.compactExecutions?'10px sans-serif':'bold 12px sans-serif';
        ctx.fillStyle='#FDE047';ctx.beginPath();ctx.arc(x,y-4,o.compactExecutions?6:9,0,Math.PI*2);ctx.fill();
        ctx.strokeStyle='#FFFFFF';ctx.lineWidth=1;ctx.stroke();ctx.fillStyle='#111827';ctx.fillText('E',x,y);
      }
      ctx.restore();
    });
  }};
  private readonly view={zOrder:()=> 'top' as const,renderer:()=>this.renderer};
  paneViews(){return[this.view];}
  attached(a:SeriesAttachedParameter<Time>){this.attachment=a;}
  detached(){this.attachment=undefined;}
  updateAllViews(){}
  set(overlay:PatternOverlay){this.overlay=overlay;this.attachment?.requestUpdate();}
}
