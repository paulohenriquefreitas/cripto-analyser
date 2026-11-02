import type { ISeriesPrimitive, IPrimitivePaneRenderer, SeriesAttachedParameter, Time, UTCTimestamp } from 'lightweight-charts';
import type { DowSnapshot, DowPivot, CandleView, EarlyDowPivot } from '../api/threeCandlesApi';

export type DowOverlay = { snapshot?: DowSnapshot | null; visible: boolean; labels: boolean; allFractals?: boolean; candles?: CandleView['candles']; earlyPivots?: readonly EarlyDowPivot[] };

/** Rendering only: every point, edge and classification comes from the Java Dow observer. */
export class DowStructurePrimitive implements ISeriesPrimitive<Time> {
  private attachment?: SeriesAttachedParameter<Time>;
  private overlay: DowOverlay = {visible:false,labels:true};
  private color: string;
  private readonly micro: boolean;
  constructor(color = '#E5E7EB', micro = false) { this.color=color; this.micro=micro; }
  private point(p: DowPivot) {
    const a=this.attachment;if(!a)return;
    const x=a.chart.timeScale().timeToCoordinate(p.extreme.extremeTimeMsc/1000 as UTCTimestamp);
    const y=a.series.priceToCoordinate(p.extreme.price);
    if(x==null || y==null)return;
    return {x,y};
  }
  private known() {
    const s=this.overlay.snapshot;
    return s?.pivots.filter(p=>p.extreme.confirmedAtMsc<=s.availableAtMsc)??[];
  }
  private readonly renderer:IPrimitivePaneRenderer = {draw:target=>{
    const {snapshot:s,visible,labels}=this.overlay;if(!s||!visible||!this.attachment)return;
    target.useMediaCoordinateSpace(({context:ctx})=>{
      ctx.save();ctx.strokeStyle=this.color;ctx.fillStyle=this.color;ctx.lineWidth=1;ctx.setLineDash(this.micro?[5,4]:[]);
      const pivots=this.known(),byId=new Map(pivots.map(p=>[p.extreme.id,p]));
      for(const m of s.movements) {
        if(m.knownAtMsc>s.availableAtMsc)continue;
        const from=byId.get(m.fromId),to=byId.get(m.toId);if(!from||!to)continue;
        const a=this.point(from),b=this.point(to);if(!a||!b)continue;
        ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();
      }
      ctx.font='11px sans-serif';
      const opening='opening' in s?s.opening:undefined;
      if(this.micro && opening?.status==='CONNECTED' && opening.knownAtMsc!=null && opening.knownAtMsc<=s.availableAtMsc) {
        const anchor=opening.anchors.find(a=>a.id===opening.fromId),to=byId.get(opening.toId??'');
        if(anchor && to && anchor.observedAtMsc<=s.availableAtMsc) {
          const x=this.attachment!.chart.timeScale().timeToCoordinate(anchor.formationTimeMsc/1000 as UTCTimestamp);
          const y=this.attachment!.series.priceToCoordinate(anchor.price),end=this.point(to);
          if(x!=null && y!=null && end) {
            ctx.strokeStyle='#F59E0B';ctx.fillStyle='#F59E0B';ctx.setLineDash([2,5]);
            ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(end.x,end.y);ctx.stroke();
            ctx.beginPath();ctx.arc(x,y,5,0,2*Math.PI);ctx.stroke();
            ctx.fillText('Abertura (referencia, nao pivo)',x+8,y+(anchor.type==='TOP'?-12:20));
            ctx.strokeStyle=this.color;ctx.fillStyle=this.color;ctx.setLineDash([5,4]);
          }
        }
      }
      const selected='selectedPivotIds' in s?s.selectedPivotIds:undefined;
      const markers=this.micro && selected && !this.overlay.allFractals ? pivots.filter(p=>selected.includes(p.extreme.id)) : pivots;
      for(const p of markers) {
        const point=this.point(p);if(!point)continue;
        const {x,y}=point;ctx.globalAlpha=p.participation==='STRUCTURE'?1:0.5;
        ctx.beginPath();ctx.arc(x,y,p.participation==='STRUCTURE'?3:2,0,2*Math.PI);
        if(p.participation==='STRUCTURE')ctx.fill();else ctx.stroke();
        if(labels)ctx.fillText((this.micro?'micro ':'')+(p.classification==='FIRST'?'?':p.classification==='EQUAL'?'=':p.classification),x+5,y+(p.extreme.type==='TOP'?-8:15));
      }
      // Label the actual confirmation candles; never infer missing candles from wall-clock offsets.
      if(!this.micro && this.overlay.candles) {
        const bars=this.overlay.candles.filter(b=>b.closed&&b.timeframeMinutes===5&&b.candle.symbol===s.symbol&&b.availableAtMsc<=s.availableAtMsc)
          .slice().sort((a,b)=>a.candle.bucketStartTimeMsc-b.candle.bucketStartTimeMsc);
        const indices=new Map(bars.map((b,i)=>[b.candle.bucketStartTimeMsc,i]));
        const drawn=new Set<string>();
        ctx.globalAlpha=1;ctx.font='bold 12px sans-serif';ctx.textAlign='center';
        for(const p of pivots) {
          const top=p.extreme.type==='TOP';
          ctx.fillStyle=top?'#FF3030':'#00E676';
          const index=indices.get(p.extreme.extremeTimeMsc);
          if(index==null)continue;
          const group=bars.slice(index,index+3);
          if(group.length!==3||group[2].availableAtMsc!==p.extreme.confirmedAtMsc
            ||group.some(b=>Math.floor(b.candle.bucketStartTimeMsc/86400000)!==Math.floor(p.extreme.extremeTimeMsc/86400000)))continue;
          group.forEach((bar,i)=>{
            const role=['C1','C2','C3'][i],key=bar.candle.bucketStartTimeMsc+':'+p.extreme.type+':'+role;
            if(drawn.has(key))return;
            const x=this.attachment!.chart.timeScale().timeToCoordinate(bar.candle.bucketStartTimeMsc/1000 as UTCTimestamp);
            const y=this.attachment!.series.priceToCoordinate(top?bar.candle.high:bar.candle.low);
            if(x==null||y==null)return;
            drawn.add(key);ctx.fillText(role,x,y+(top?-24:30));
          });
        }
        ctx.fillStyle=this.color;ctx.textAlign='start';ctx.font='11px sans-serif';
      }
      if(!this.micro && this.overlay.earlyPivots) {
        ctx.globalAlpha=1;ctx.font='bold 12px sans-serif';ctx.textAlign='center';
        for(const p of this.overlay.earlyPivots) {
          if(p.status!=='CANDIDATE_2_1'||p.availableAtMsc>s.availableAtMsc)continue;
          const top=p.type==='TOP';
          ctx.fillStyle=top?'#FF3030':'#00E676';
          const d=this.overlay.candles?.find(b=>b.timeframeMinutes===5&&b.candle.symbol===s.symbol
            &&b.candle.bucketStartTimeMsc===p.rightC.candle.bucketStartTimeMsc+300000&&b.availableAtMsc<=s.availableAtMsc);
          const marks=[{bar:p.origin,label:'C1'},{bar:p.rightC,label:'C2'},...(d?[{bar:d,label:'C3'}]:[])];
          for(const {bar,label} of marks) {
            if(bar.availableAtMsc>s.availableAtMsc)continue;
            const x=this.attachment!.chart.timeScale().timeToCoordinate(bar.candle.bucketStartTimeMsc/1000 as UTCTimestamp);
            const y=this.attachment!.series.priceToCoordinate(top?bar.candle.high:bar.candle.low);
            if(x==null||y==null)continue;
            ctx.fillText(label,x,y+(top?-24:30));
          }
        }
        ctx.fillStyle=this.color;ctx.textAlign='start';ctx.font='11px sans-serif';
      }
      ctx.globalAlpha=0.8;ctx.setLineDash([3,3]);
      for(const e of s.events) {
        if(e.atMsc>s.availableAtMsc || e.candleTimeMsc==null || !e.pivotId || !['RESISTANCE_BROKEN','SUPPORT_BROKEN'].includes(e.kind))continue;
        const pivot=byId.get(e.pivotId);if(!pivot)continue;
        const a=this.point(pivot),x=this.attachment!.chart.timeScale().timeToCoordinate(e.candleTimeMsc/1000 as UTCTimestamp);
        if(!a||x==null)continue;
        ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(x,a.y);ctx.stroke();
        ctx.fillText(e.kind==='RESISTANCE_BROKEN'?'R↑':'R↓',x+4,a.y-4);
      }
      ctx.restore();
    });
  }};
  private readonly view={zOrder:()=> 'top' as const,renderer:()=>this.renderer};
  paneViews(){return [this.view];}
  attached(a:SeriesAttachedParameter<Time>){this.attachment=a;}
  detached(){this.attachment=undefined;}
  updateAllViews(){}
  set(overlay:DowOverlay){this.overlay=overlay;this.attachment?.requestUpdate();}
  pick(x:number,y:number):DowPivot|undefined {
    if(!this.overlay.visible)return;
    return this.known().map(p=>({p,point:this.point(p)})).filter(p=>p.point)
      .map(({p,point})=>({p,d:Math.hypot(point!.x-x,point!.y-y)})).filter(p=>p.d<=9).sort((a,b)=>a.d-b.d)[0]?.p;
  }
}
