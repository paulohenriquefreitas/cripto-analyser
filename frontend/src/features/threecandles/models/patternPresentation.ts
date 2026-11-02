import type { PatternStudySnapshot } from '../api/threeCandlesApi';
import type { ThreeCandleVisualSnapshot } from './threeCandlePatternAudit';

export type PatternDisplayMode = 'SELECTED' | 'TRIGGERS' | 'ALL' | 'SETUPS' | 'MONITORING';
type Candidate = PatternStudySnapshot['candidates'][number];
export const invalidationReason = (c: Candidate) => c.events.filter(e=>/INVALIDADO|REJEITADA|INCONCLUSIVO|INDETERMINADO/.test(e.kind)).map(e=>e.reason).join('; ');

/** Presentation only: never merges records, infers stages or evaluates future outcomes. */
export function presentPatterns(audit: ThreeCandleVisualSnapshot | null | undefined, study: PatternStudySnapshot | null | undefined, mode: PatternDisplayMode, selected: string) {
  return {
    snapshot: audit ? {...audit, patterns: mode==='TRIGGERS'?[]:mode==='ALL'?audit.patterns:mode==='MONITORING'?audit.patterns.filter(p=>study?.candidates.some(c=>c.id===p.id&&(c.trigger.state==='ALCANCADO_HIPOTETICO'||c.recognizedAtMsc!=null&&c.candle1.candle.bucketStartTimeMsc+900000>study.availableAtMsc))):mode==='SETUPS'?audit.patterns.filter(p=>study?.candidates.some(c=>c.id===p.id&&c.trigger.state==='ALCANCADO_HIPOTETICO')):audit.patterns.filter(p=>p.id===selected)} : audit,
    executions: study?.candidates.flatMap(c=>(mode!=='SELECTED'||c.id===selected) && c.trigger.state==='ALCANCADO_HIPOTETICO' && c.trigger.reachedAtMsc!=null && c.trigger.reachedAtMsc<=study.availableAtMsc && c.trigger.level!=null && c.trigger.evidence
      ? [{patternId:c.id,direction:c.direction,candleTimeMsc:Math.floor(c.trigger.evidence.startTimeMsc/300000)*300000,availableAtMsc:c.trigger.reachedAtMsc,level:c.trigger.level}] : []) ?? [],
  };
}

export function patternDuplicationAudit(candidates: readonly Candidate[]) {
  const groups = (key:(c:Candidate)=>string|null) => {
    const map=new Map<string,Candidate[]>();
    for(const c of candidates){const k=key(c);if(k!=null)map.set(k,[...(map.get(k)??[]),c]);}
    return [...map.entries()].map(([key,items])=>({key,items}));
  };
  const shared = (key:(c:Candidate)=>string|null) => {
    const repeated=groups(key).filter(g=>g.items.length>1);
    return {groups:repeated.length,candidates:repeated.reduce((n,g)=>n+g.items.length,0),excess:repeated.reduce((n,g)=>n+g.items.length-1,0)};
  };
  const sequences=groups(c=>[c.candle1Id,c.candle2Id??'?',c.candle3Id??'?'].join('|'));
  // Exact same interval; no invented price tolerance. Side/C1 keep unrelated episodes separate.
  const regions=groups(c=>[c.candle1Id,c.direction,c.region.low,c.region.high].join('|')).filter(g=>new Set(g.items.map(c=>c.region.id)).size>1);
  const variants=(['A_FALHA_RENOVACAO','B_FALSO_ROMPIMENTO','FECHAMENTO_NAO_CONTINUA_C1'] as const).map(variant=>{
    const items=candidates.filter(c=>c.negationVariants.includes(variant));
    return {variant,total:items.length,tested:items.filter(c=>c.events.some(e=>e.kind==='REGIAO_TESTADA')).length,invalidated:items.filter(c=>c.state==='TESTE_INVALIDADO').length};
  });
  return {total:candidates.length,sequences:sequences.length,sequenceDirections:new Set(candidates.map(c=>[c.direction,c.candle1Id,c.candle2Id??'?',c.candle3Id??'?'].join('|'))).size,
    sharedCandles:[shared(c=>c.candle1Id),shared(c=>c.candle2Id),shared(c=>c.candle3Id)],sameRegionGroups:regions.length,sameRegionCandidates:regions.reduce((n,g)=>n+g.items.length,0),
    tested:candidates.filter(c=>c.events.some(e=>e.kind==='REGIAO_TESTADA')).length,reached:candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO').length,
    invalidated:candidates.filter(c=>c.state==='TESTE_INVALIDADO').length,rejected:candidates.filter(c=>c.state==='REJEITADO').length,
    both:candidates.filter(c=>c.negationVariants.length===2).length,variants};
}
