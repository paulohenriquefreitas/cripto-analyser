/** Recognizer evidence only. Rendering and audit never infer Alvo/Nega/Testa from OHLC. */
export type PatternObservation = {
  candle: {symbol:string;bucketStartTimeMsc:number;open:number;high:number;low:number;close:number};
  timeframeMinutes:5;closed:boolean;availableAtMsc:number;
};
export type PatternStage = {
  number:1|2|3;role:'ALVO'|'NEGA'|'TESTA';observation:PatternObservation;
  referencePrice:number;availableAtMsc:number;reason:string;
};
export type ThreeCandleVisualPattern = {
  id:string;direction:'BUY'|'SELL';state:'CANDLE_1'|'CANDLES_1_2'|'COMPLETE';stages:PatternStage[];
};
export type ThreeCandleVisualSnapshot = {
  symbol:string;timeframeMinutes:5;sessionDate:string;availableAtMsc:number;
  rulesStatus:'PENDING_DEFINITION'|'DEFINED';criterionId:string|null;patterns:ThreeCandleVisualPattern[];
};
export type PatternOverlay = {
  snapshot?:ThreeCandleVisualSnapshot|null;
  candles?:readonly {candle:PatternObservation['candle'];timeframeMinutes:number;closed:boolean;availableAtMsc:number}[];
  visible:boolean;numbers:boolean;highlights:boolean;
  compactExecutions?:boolean;
  executions?:readonly {patternId:string;direction:'BUY'|'SELL';candleTimeMsc:number;availableAtMsc:number;level:number}[];
};

/** Cut to knowledge time even if a caller accidentally supplies a future-containing snapshot. */
export function knownPatternStages(snapshot:ThreeCandleVisualSnapshot,cutoff=snapshot.availableAtMsc) {
  const marks:{patternId:string;direction:'BUY'|'SELL';stage:PatternStage}[]=[];
  if(snapshot.rulesStatus!=='DEFINED')return marks;
  const at=Math.min(cutoff,snapshot.availableAtMsc);
  for(const pattern of snapshot.patterns) {
    let previousTime=-1,previousKnown=-1;
    for(let i=0;i<pattern.stages.length;i++) {
      const stage=pattern.stages[i],b=stage.observation;
      if(stage.number!==i+1 || stage.role!==['ALVO','NEGA','TESTA'][i] || stage.availableAtMsc>at
        || stage.availableAtMsc<b.availableAtMsc || b.candle.symbol!==snapshot.symbol || b.timeframeMinutes!==5
        || b.candle.bucketStartTimeMsc<=previousTime || stage.availableAtMsc<previousKnown)break;
      marks.push({patternId:pattern.id,direction:pattern.direction,stage});
      previousTime=b.candle.bucketStartTimeMsc;previousKnown=stage.availableAtMsc;
    }
  }
  return marks;
}
