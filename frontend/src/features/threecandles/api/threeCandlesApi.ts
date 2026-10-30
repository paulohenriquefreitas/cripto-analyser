import { z } from 'zod';
import { httpClient } from '@/common/api/httpClient';
import type { CandleTimeBasis } from '../models/candleTime';

const updateSchema = z.object({
  candle: z.object({ symbol: z.string(), bucketStartTimeMsc: z.number().int().safe(),
    open: z.number().finite().positive(), high: z.number().finite(), low: z.number().finite(), close: z.number().finite().positive(),
  }).refine(c => c.low <= Math.min(c.open, c.close) && c.high >= Math.max(c.open, c.close)),
  timeframeMinutes: z.union([z.literal(5), z.literal(15)]), closed: z.boolean(), availableAtMsc: z.number().int().safe(),
});
const levelSchema = z.object({
      id: z.string(), timeframeMinutes: z.union([z.literal(5), z.literal(60)]).default(5), type: z.enum(['TOP', 'BOTTOM']), price: z.number().finite().positive(),
      extremeTimeMsc: z.number().int().safe(), confirmedAtMsc: z.number().int().safe(),
      relevance: z.string().nullable(),
      state: z.enum(['CONFIRMED', 'BROKEN']).optional(), brokenAtMsc: z.number().int().safe().nullable().optional(),
      region: z.object({ high: z.number().finite(), low: z.number().finite(), midpoint: z.number().finite(), basis: z.literal('PIVOT_CANDLE') }),
    });
export type H1DetectionMode = 'STRICT_2_2' | 'EXPERIMENTAL_1_1';
const comparisonSchema = z.object({
  strictCandidates: z.array(levelSchema), oneOneCandidates: z.array(levelSchema), additionalCandidates: z.array(levelSchema),
  baselineMatches: z.record(z.string()), regionWidthPoints: z.number().nonnegative(), outsideWindowCandidates: z.number().int().nonnegative(),
  evaluations: z.array(z.object({ originMsc: z.number().int().safe(), type: z.enum(['TOP','BOTTOM']), price: z.number().positive(),
    decision: z.enum(['CONFIRMED_1_1','NOT_EXTREME_1_1','UNCONFIRMED_1_1']), reason: z.string(), confirmedAtMsc: z.number().int().safe().nullable() })),
  regions: z.array(z.object({ id: z.string(), low: z.number(), high: z.number(), pivotIds: z.array(z.string()),
    tests: z.array(z.object({ startMsc: z.number().int().safe(), endMsc: z.number().int().safe(), knownAtMsc: z.number().int().safe(), hours: z.number().int().positive(),
      reactionUpPoints: z.number().nonnegative(), reactionDownPoints: z.number().nonnegative(), measuredThroughMsc: z.number().int().safe() })) })),
});
const preparationCriterion = z.enum(['PREOPEN_THREE_SESSIONS_STRICT_PIVOTS_GROUP_5_POINTS','PREOPEN_THREE_SESSIONS_EXPERIMENTAL_1_1_WITH_2_2']);
const dowSchema = z.object({
  symbol:z.string(), timeframeMinutes:z.literal(5), availableAtMsc:z.number().int().safe(),
  state:z.enum(['UP','DOWN','UNDEFINED','TRANSITION']), criterion:z.literal('EXPERIMENTAL_PAIRED_PIVOTS_CLOSE_BREAK_V1'),
  pivots:z.array(z.object({extreme:levelSchema,classification:z.enum(['FIRST','EQUAL','HH','HL','LH','LL']),
    comparisonId:z.string().nullable(),comparisonPrice:z.number().nullable(),reason:z.string(),participation:z.enum(['STRUCTURE','LOCAL','SUPERSEDED'])})),
  movements:z.array(z.object({id:z.string(),fromId:z.string(),toId:z.string(),knownAtMsc:z.number().int().safe(),kind:z.enum(['UP','DOWN'])})),
  events:z.array(z.object({id:z.string(),atMsc:z.number().int().safe(),kind:z.string(),pivotId:z.string().nullable(),
    affectedMovementId:z.string().nullable(),stateBefore:z.string(),stateAfter:z.string(),reason:z.string(),
    candleTimeMsc:z.number().int().safe().nullable().optional(),observedClose:z.number().finite().nullable().optional()})),
});
const openingSchema = z.object({status:z.enum(['UNAVAILABLE','WAITING','CONNECTED','REJECTED']),
    anchors:z.array(z.object({id:z.string(),type:z.enum(['TOP','BOTTOM']),price:z.number().positive(),formationTimeMsc:z.number().int().safe(),observedAtMsc:z.number().int().safe()})),
    fromId:z.string().nullable(),toId:z.string().nullable(),knownAtMsc:z.number().int().safe().nullable(),reason:z.string(),
    events:z.array(z.object({atMsc:z.number().int().safe(),kind:z.string(),reason:z.string()}))});
const microDowSchema = dowSchema.extend({criterion:z.literal('EXPERIMENTAL_MICRO_STRICT_1_1_V1'),
  selectedPivotIds:z.array(z.string()).optional(),
  movementHistory:dowSchema.shape.movements.optional(),
  decisions:z.array(z.object({pivotId:z.string(),atMsc:z.number().int().safe(),action:z.string(),reason:z.string(),previousTerminalId:z.string().nullable(),replacedMovementId:z.string().nullable(),movementId:z.string().nullable()})).optional(),
  openingHistory:z.array(openingSchema).optional(),
  opening:openingSchema.optional(),
});
export type DowSnapshot = z.infer<typeof dowSchema> | z.infer<typeof microDowSchema>;
export type DowPivot = DowSnapshot['pivots'][number];
const dowFiftyState = z.enum(['AGUARDANDO_ONDA','ONDA_CONFIRMADA','MONITORANDO_50','ROMPIMENTO_EM_AVALIACAO','REJEICAO_EM_AVALIACAO','ONDA_ENCERRADA']);
const dowFiftySchema = z.object({
  symbol:z.string(),timeframeMinutes:z.literal(5),availableAtMsc:z.number().int().safe(),
  criterion:z.enum(['SEALED_BY_NEXT_STRUCTURAL_PIVOT_V1','CONFIRMED_MOVEMENT_V2']),approachTolerancePoints:z.number().finite().nonnegative(),
  breakPolicy:z.literal('EXPERIMENTAL_M5_CLOSE'),rejectionPolicy:z.literal('UNVALIDATED_RANGE_TOUCH_CLOSE_SAME_SIDE'),
  state:dowFiftyState,currentWaveId:z.string().nullable(),
  formingWave:z.object({id:z.string(),direction:z.enum(['UP','DOWN']),initial:levelSchema,brokenReference:levelSchema,
    high:z.number().finite().positive(),low:z.number().finite().positive(),level50:z.number().finite().positive(),
    extremeTimeMsc:z.number().int().safe(),recognizedAtMsc:z.number().int().safe(),availableAtMsc:z.number().int().safe(),
    criterion:z.literal('OBSERVED_BREAK_FROM_CONFIRMED_TERMINAL_V1')}).nullable().optional(),
  references:z.array(z.object({wave:z.object({id:z.string(),direction:z.enum(['UP','DOWN']),initial:levelSchema,terminal:levelSchema,
    high:z.number().finite().positive(),low:z.number().finite().positive(),level50:z.number().finite().positive(),recognizedAtMsc:z.number().int().safe(),sourceEventId:z.string(),sealingPivotId:z.string()}),
    state:dowFiftyState,endedAtMsc:z.number().int().safe().nullable(),lastEvent:z.string(),approachSide:z.enum(['TOP_SIDE','BOTTOM_SIDE','UNKNOWN']),breakStatus:z.string(),rejectionStatus:z.string()})),
  events:z.array(z.object({id:z.string(),atMsc:z.number().int().safe(),waveId:z.string().nullable(),kind:z.string(),stateBefore:dowFiftyState,stateAfter:dowFiftyState,
    candleTimeMsc:z.number().int().safe().nullable(),close:z.number().finite().nullable(),detail:z.string()})),
  observations:z.array(z.object({atMsc:z.number().int().safe(),candleTimeMsc:z.number().int().safe(),waveId:z.string().nullable(),state:dowFiftyState,close:z.number().finite(),
    distance50:z.number().finite().nonnegative().nullable(),distanceTop:z.number().finite().nonnegative().nullable(),distanceBottom:z.number().finite().nonnegative().nullable(),
    approachSide:z.string(),breakStatus:z.string(),rejectionStatus:z.string(),reason:z.string()})),
});
export type DowFiftySnapshot = z.infer<typeof dowFiftySchema>;
const microFiftyWaveSchema = z.object({id:z.string(),fromId:z.string(),toId:z.string(),direction:z.enum(['UP','DOWN']),
  initial:levelSchema,terminal:levelSchema,high:z.number().finite().positive(),low:z.number().finite().positive(),
  level50:z.number().finite().positive(),availableAtMsc:z.number().int().safe(),state:z.literal('CONFIRMED')});
const microDowFiftySchema = z.object({symbol:z.string(),timeframeMinutes:z.literal(5),availableAtMsc:z.number().int().safe(),
  criterion:z.literal('CONFIRMED_MICRO_MOVEMENT_50_V1'),state:z.enum(['WAITING_WAVE','CONFIRMED']),currentWaveId:z.string().nullable(),
  references:z.array(z.object({wave:microFiftyWaveSchema,supersededAtMsc:z.number().int().safe().nullable(),supersededByWaveId:z.string().nullable()})),
  events:z.array(z.object({id:z.string(),kind:z.enum(['REFERENCE_CREATED','REFERENCE_REPLACED']),atMsc:z.number().int().safe(),waveId:z.string(),previousWaveId:z.string().nullable()}))});
export type MicroDowFiftySnapshot = z.infer<typeof microDowFiftySchema>;
export type ReplayMode = 'CLOSED_CANDLE' | 'PROGRESSIVE_M1';
const openingReferenceSchema = z.object({price:z.number().finite().positive(), formationTimeMsc:z.number().int().safe(),
  availableAtMsc:z.number().int().safe(), source:z.string()});
const openingLegSchema = z.object({direction:z.enum(['UP','DOWN']),state:z.literal('EM_FORMACAO'),
  origin:openingReferenceSchema,destination:openingReferenceSchema,amplitudePoints:z.number().nonnegative(),
  availableAtMsc:z.number().int().safe(),intrabarOrder:z.enum(['UNKNOWN_SAME_M5','ORDERED_M5_BARS'])});
export const openingStructureSchema = z.object({symbol:z.string(),sessionDate:z.string(),availableAtMsc:z.number().int().safe(),
  state:z.enum(['UNAVAILABLE','WAITING_DIRECTION','EM_FORMACAO']),unit:z.literal('WIN_INDEX_POINTS'),timeBasis:z.literal('MT5_WALL_CLOCK'),
  previousSession:z.object({date:z.string(),close:openingReferenceSchema,high:openingReferenceSchema,low:openingReferenceSchema,
    officialSettlement:openingReferenceSchema.nullable(),coverage:z.string(),settlementStatus:z.string()}).nullable(),
  tradedOpen:openingReferenceSchema.nullable(),gap:z.object({kind:z.literal('GAP'),points:z.number().finite(),
    from:openingReferenceSchema,to:openingReferenceSchema,availableAtMsc:z.number().int().safe()}).nullable(),
  observedHigh:openingReferenceSchema.nullable(),observedLow:openingReferenceSchema.nullable(),initialLeg:openingLegSchema.nullable(),
  legHistory:z.array(openingLegSchema),windows:z.array(z.object({minutes:z.union([z.literal(15),z.literal(30),z.literal(45)]),
    availableAtMsc:z.number().int().safe().nullable(),amplitudePoints:z.number().nonnegative().nullable(),status:z.enum(['COMPLETE','EM_FORMACAO','INCOMPLETE'])})),
  thresholds:z.array(z.object({minimumPoints:z.union([z.literal(0),z.literal(250),z.literal(300)]),reached:z.boolean(),firstReachedAtMsc:z.number().int().safe().nullable()})),
  coverage:z.string(),
}).superRefine((s,ctx)=>{
  const refs=[s.tradedOpen,s.observedHigh,s.observedLow,s.previousSession?.close,s.previousSession?.high,s.previousSession?.low,s.previousSession?.officialSettlement,
    s.initialLeg?.origin,s.initialLeg?.destination,...s.legHistory.flatMap(l=>[l.origin,l.destination])].filter(r=>r!=null);
  if(refs.some(r=>r.availableAtMsc>s.availableAtMsc || r.formationTimeMsc+300000>r.availableAtMsc)
    || [...s.legHistory,...(s.initialLeg?[s.initialLeg]:[])].some(l=>l.availableAtMsc>s.availableAtMsc || l.origin.availableAtMsc>l.availableAtMsc || l.destination.availableAtMsc>l.availableAtMsc
      || l.amplitudePoints!==Math.abs(l.destination.price-l.origin.price))
    || s.windows.some(w=>w.availableAtMsc!=null && w.availableAtMsc>s.availableAtMsc)
    || s.thresholds.some(t=>t.firstReachedAtMsc!=null && t.firstReachedAtMsc>s.availableAtMsc)
    || (s.gap && (s.gap.availableAtMsc>s.availableAtMsc || s.gap.points!==s.gap.to.price-s.gap.from.price)))
    ctx.addIssue({code:z.ZodIssueCode.custom,message:'Contexto de abertura inconsistente ou futuro.'});
});
export type OpeningStructureSnapshot = z.infer<typeof openingStructureSchema>;
export const viewSchema = z.object({
  id: z.string().optional(), symbol: z.string(), timeframeMinutes: z.union([z.literal(5), z.literal(15)]),
  availableAtMsc: z.number().int().safe().optional(),
  missingM1Times: z.array(z.number().int().safe()).optional(),
  replayMode: z.enum(['CLOSED_CANDLE','PROGRESSIVE_M1']).optional(),
  status: z.string(), speed: z.number().optional(), lastTickMsc: z.number().nullable().optional(), candles: z.array(updateSchema),
  openingStructure:openingStructureSchema.nullable().optional(),
  dow:dowSchema.nullable().optional(),
  dowFifty:dowFiftySchema.nullable().optional(),
  microDow:microDowSchema.nullable().optional(),
  microDowFifty:microDowFiftySchema.nullable().optional(),
  structure: z.object({
    levels: z.array(levelSchema),
    confirmedPivots: z.array(levelSchema).optional(),
    totalActive: z.number().int().nonnegative(), maxLevels: z.number().int().min(1).max(12),
    h1History: z.object({previousSessions: z.number().int().nonnegative(), sufficient: z.boolean(), alignment: z.literal('NATIVE_FEED_HOUR_NO_SHIFT')}).optional(),
    preparation: z.object({symbol: z.string(), sessionDate: z.string(), sessionOpenMsc: z.number().int().safe(),
      sourceSessions: z.array(z.string()), sufficient: z.boolean(), criterion: preparationCriterion,
      detectionMode: z.enum(['STRICT_2_2','EXPERIMENTAL_1_1']).optional(), experimental: comparisonSchema.nullable().optional(),
      candidates: z.array(levelSchema), levels: z.array(levelSchema), groups: z.record(z.array(z.string())),
    }).nullable().optional(),
    selection: z.enum(['NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED','PREOPEN_THREE_SESSIONS_STRICT_PIVOTS_GROUP_5_POINTS','PREOPEN_THREE_SESSIONS_EXPERIMENTAL_1_1_WITH_2_2']), supported: z.boolean(),
  }).optional(),
}).superRefine((view, ctx) => {
  if(view.microDowFifty) {
    const f=view.microDowFifty,micro=view.microDow,at=view.availableAtMsc??-1;
    const invalid=!micro || view.timeframeMinutes!==5 || f.symbol!==view.symbol || f.availableAtMsc!==at
      || f.currentWaveId!==(f.references.at(-1)?.wave.id??null) || f.state!==(f.references.length?'CONFIRMED':'WAITING_WAVE')
      || new Set(f.references.map(r=>r.wave.id)).size!==f.references.length || f.events.length!==f.references.length
      || f.currentWaveId!==(micro?.movements.at(-1)?.id??null)
      || f.references.some((r,i)=>{
        const w=r.wave,a=micro?.pivots.find(p=>p.extreme.id===w.fromId)?.extreme,b=micro?.pivots.find(p=>p.extreme.id===w.toId)?.extreme;
        const m=micro?.movementHistory?.find(m=>m.id===w.id),next=f.references[i+1]?.wave,e=f.events[i];
        const d=micro?.decisions?.find(d=>d.movementId===w.id && ['SEGMENT_CREATED','TERMINAL_REPLACED'].includes(d.action));
        return !a || !b || !m || !d || d.atMsc!==w.availableAtMsc || !e
          || w.id!==`${w.fromId}->${w.toId}` || m.fromId!==w.fromId || m.toId!==w.toId || m.kind!==w.direction || m.knownAtMsc!==w.availableAtMsc
          || a.type===b.type || a.extremeTimeMsc>=b.extremeTimeMsc || w.direction!==(b.type==='TOP'?'UP':'DOWN')
          || (w.direction==='UP'?b.price<=a.price:b.price>=a.price) || w.availableAtMsc!==Math.max(a.confirmedAtMsc,b.confirmedAtMsc) || w.availableAtMsc>at
          || [a,b].some((p,j)=>{const own=j===0?w.initial:w.terminal;return p.id!==`${view.symbol}:5:micro:${p.extremeTimeMsc}:${p.type}`
            || p.relevance!=='MICRO_1_1' || own.id!==p.id || own.type!==p.type || own.price!==p.price || own.extremeTimeMsc!==p.extremeTimeMsc || own.confirmedAtMsc!==p.confirmedAtMsc;})
          || w.high!==Math.max(a.price,b.price) || w.low!==Math.min(a.price,b.price) || w.level50!==(w.high+w.low)/2
          || r.supersededAtMsc!==(next?.availableAtMsc??null) || r.supersededByWaveId!==(next?.id??null)
          || (next && next.availableAtMsc<w.availableAtMsc)
          || e.waveId!==w.id || e.atMsc!==w.availableAtMsc || e.previousWaveId!==(f.references[i-1]?.wave.id??null)
          || e.kind!==(i===0?'REFERENCE_CREATED':'REFERENCE_REPLACED');
      });
    if(invalid)ctx.addIssue({code:z.ZodIssueCode.custom,path:['microDowFifty'],message:'50% micro requer uma onda micro confirmada, selecionada e causalmente disponível.'});
  }
  if(view.openingStructure && (view.timeframeMinutes!==5 || view.openingStructure.symbol!==view.symbol
    || view.openingStructure.availableAtMsc!==view.availableAtMsc))
    ctx.addIssue({code:z.ZodIssueCode.custom,path:['openingStructure'],message:'Contexto de abertura fora da sessao.'});
  if(view.dowFifty) {
    const f=view.dowFifty,at=view.availableAtMsc??view.candles.at(-1)?.availableAtMsc??-1,dow=view.dow;
    const refs=new Map(f.references.map(r=>[r.wave.id,r]));
    const current=f.currentWaveId==null?undefined:refs.get(f.currentWaveId);
    const invalid=!dow || view.timeframeMinutes!==5 || f.symbol!==view.symbol || f.availableAtMsc!==at || refs.size!==f.references.length
      || (f.currentWaveId!=null && (!current || current.state==='ONDA_ENCERRADA' || current.endedAtMsc!=null || f.state!==current.state))
      || (f.currentWaveId==null && f.state!=='AGUARDANDO_ONDA')
      || f.references.some(r=>{
        const w=r.wave,m=dow?.movements.find(m=>m.id===w.id),e=dow?.events.find(e=>e.id===w.sourceEventId);
        const a=dow?.pivots.find(p=>p.extreme.id===w.initial.id)?.extreme,b=dow?.pivots.find(p=>p.extreme.id===w.terminal.id)?.extreme;
        const seal=dow?.pivots.find(p=>p.extreme.id===w.sealingPivotId)?.extreme;
        const currentMovement=f.criterion==='CONFIRMED_MOVEMENT_V2';
        return !e || !a || !b || !seal || (currentMovement
          ? w.id!==`${a.id}\u2192${b.id}` || w.direction!==(b.type==='TOP'?'UP':'DOWN') || seal.id!==b.id
            || !['STRUCTURE_PIVOT_SELECTED','TERMINAL_EXTENDED'].includes(e.kind) || e.pivotId!==b.id || e.atMsc!==w.recognizedAtMsc || b.confirmedAtMsc!==w.recognizedAtMsc
          : !m || m.fromId!==a.id || m.toId!==b.id || m.kind!==w.direction
          || e.kind!=='STRUCTURE_PIVOT_SELECTED' || e.affectedMovementId!==w.id || e.pivotId!==seal.id || e.atMsc!==w.recognizedAtMsc
          || seal.confirmedAtMsc!==w.recognizedAtMsc || seal.type===b.type || seal.extremeTimeMsc<=b.extremeTimeMsc)
          || [a,b].some((p,i)=>{const own=i===0?w.initial:w.terminal;return p.price!==own.price||p.extremeTimeMsc!==own.extremeTimeMsc||p.confirmedAtMsc!==own.confirmedAtMsc;})
          || [a,b,seal].some(p=>p.id!==`${view.symbol}:5:${p.extremeTimeMsc}:${p.type}`)
          || w.high<=w.low || a.type===b.type || a.extremeTimeMsc>=b.extremeTimeMsc || a.confirmedAtMsc>w.recognizedAtMsc || b.confirmedAtMsc>w.recognizedAtMsc
          || w.recognizedAtMsc>at || w.high!==Math.max(a.price,b.price) || w.low!==Math.min(a.price,b.price) || w.level50!==(w.high+w.low)/2
          || (r.endedAtMsc!=null && (r.endedAtMsc>at || r.endedAtMsc<w.recognizedAtMsc))
          || (r.state==='ONDA_ENCERRADA' && (r.endedAtMsc==null || f.currentWaveId===w.id));
      })
      || f.events.some(e=>e.atMsc>at || (e.waveId!=null&&!refs.has(e.waveId)))
      || f.observations.some(o=>o.atMsc>at || o.candleTimeMsc+300000>o.atMsc || (o.waveId!=null && (!refs.has(o.waveId)
        || (o.reason==='MONITORED' && refs.get(o.waveId)!.wave.recognizedAtMsc>o.candleTimeMsc))));
    if(invalid)ctx.addIssue({code:z.ZodIssueCode.custom,path:['dowFifty'],message:'50% Dow sem onda principal concluida e causalmente disponivel.'});
    const forming=f.formingWave;
    if(forming) {
      const movement=dow?.movements.at(-1),a=dow?.pivots.find(p=>p.extreme.id===forming.initial.id)?.extreme;
      const boundary=dow?.pivots.find(p=>p.extreme.id===forming.brokenReference.id)?.extreme;
      const up=forming.direction==='UP',extreme=up?forming.high:forming.low;
      const bars=view.candles.filter(b=>b.candle.bucketStartTimeMsc>=forming.initial.confirmedAtMsc&&b.availableAtMsc<=at);
      const bad=!movement||!a||!boundary||movement.toId!==a.id||movement.fromId!==boundary.id
        || forming.id!==`${forming.initial.id}:forming:${forming.direction}`
        || a.type!==forming.initial.type||a.extremeTimeMsc!==forming.initial.extremeTimeMsc
        || forming.extremeTimeMsc<forming.initial.confirmedAtMsc
        || a.price!==forming.initial.price||a.confirmedAtMsc!==forming.initial.confirmedAtMsc
        || boundary.price!==forming.brokenReference.price||a.confirmedAtMsc>forming.recognizedAtMsc
        || forming.availableAtMsc!==at||forming.recognizedAtMsc>at||forming.extremeTimeMsc>at
        || forming.high<=forming.low||forming.level50!==(forming.high+forming.low)/2
        || (up ? a.type!=='BOTTOM'||forming.low!==a.price||forming.high<=boundary.price
          : a.type!=='TOP'||forming.high!==a.price||forming.low>=boundary.price)
        || !bars.some(b=>b.availableAtMsc<=forming.recognizedAtMsc&&(up?b.candle.high>boundary!.price:b.candle.low<boundary!.price))
        || !bars.some(b=>b.candle.bucketStartTimeMsc===forming.extremeTimeMsc&&(up?b.candle.high:b.candle.low)===extreme)
        || bars.some(b=>up?b.candle.low<a!.price||b.candle.high>extreme:b.candle.high>a!.price||b.candle.low<extreme);
      if(bad)ctx.addIssue({code:z.ZodIssueCode.custom,path:['dowFifty','formingWave'],message:'Onda em formacao sem rompimento observado e causal.'});
    }
  }
  for(const dow of [view.dow,view.microDow]) {
    if(!dow) continue;
    const at=view.availableAtMsc??view.candles.at(-1)?.availableAtMsc??-1;
    if('opening' in dow && dow.opening) {
      const o=dow.opening;
      const target=dow.pivots.find(p=>p.extreme.id===o.toId);
      if(o.anchors.some(a=>a.observedAtMsc>at || a.observedAtMsc<a.formationTimeMsc+300000)
          || o.events.some(e=>e.atMsc>at) || (o.knownAtMsc!=null && o.knownAtMsc>at)
          || (o.status==='CONNECTED' && (!target || !o.anchors.some(a=>a.id===o.fromId)
            || o.knownAtMsc==null || o.knownAtMsc<target.extreme.confirmedAtMsc)))
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['microDow','opening'],message:'Referencia de abertura futura ou inconsistente.'});
    }
    if('decisions' in dow && dow.decisions?.some(d=>d.atMsc>at))
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['microDow','decisions'],message:'Decisao micro futura.'});
    if('movementHistory' in dow && dow.movementHistory?.some(m=>m.knownAtMsc>at))
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['microDow','movementHistory'],message:'Segmento historico futuro.'});
    const ids=new Set(dow.pivots.map(p=>p.extreme.id));
    if('selectedPivotIds' in dow && dow.selectedPivotIds) {
      const selected=dow.selectedPivotIds,byId=new Map(dow.pivots.map(p=>[p.extreme.id,p.extreme]));
      if(new Set(selected).size!==selected.length || selected.some(id=>!ids.has(id))
        || selected.some((id,i)=>i>0 && byId.has(id) && byId.has(selected[i-1])
          && (byId.get(id)!.type===byId.get(selected[i-1])!.type || byId.get(id)!.extremeTimeMsc<=byId.get(selected[i-1])!.extremeTimeMsc)))
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['microDow','selectedPivotIds'],message:'Cadeia micro inconsistente.'});
    }
    if(dow.symbol!==view.symbol || view.timeframeMinutes!==5 || dow.availableAtMsc!==at
      || ids.size!==dow.pivots.length || dow.pivots.some(p=>p.extreme.timeframeMinutes!==5
        || !p.extreme.id.startsWith(`${view.symbol}:5:`) || p.extreme.confirmedAtMsc>at || p.extreme.confirmedAtMsc<=p.extreme.extremeTimeMsc)
      || dow.movements.some(m=>m.knownAtMsc>at || !ids.has(m.fromId) || !ids.has(m.toId))
      || dow.events.some(e=>e.atMsc>at))
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['dow'],message:'Estrutura Dow incompatível com o ativo ou o instante do Replay.'});
  }
  if (!view.structure) return;
  const { levels, maxLevels } = view.structure;
  const availableAt = view.availableAtMsc ?? view.candles.at(-1)?.availableAtMsc ?? -1;
  if (view.structure.confirmedPivots?.some(l => l.confirmedAtMsc > availableAt || l.confirmedAtMsc <= l.extremeTimeMsc))
    ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','confirmedPivots'],message:'Memoria estrutural contem pivo futuro ou confirmacao invalida.'});
  for (const timeframe of [60]) {
    const count = levels.filter(l => l.timeframeMinutes === timeframe).length;
    if (count > maxLevels && !(timeframe === 60 && view.structure.preparation)) ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['structure', 'levels'],
      message: `Limite de niveis excedido em ${timeframe === 60 ? 'H1' : 'M5'}: ${count} > ${maxLevels}.` });
  }
  const preparation=view.structure.preparation;
  if(preparation) {
    if(view.availableAtMsc===undefined) ctx.addIssue({code:z.ZodIssueCode.custom,path:['availableAtMsc'],message:'Preparacao H1 requer o relogio explicito da sessao.'});
    const cutoff=Math.min(availableAt,preparation.sessionOpenMsc);
    const experimental=preparation.experimental;
    if (preparation.detectionMode==='EXPERIMENTAL_1_1' && !experimental)
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','experimental'],message:'Modo experimental requer auditoria 1+1 e referencia 2+2.'});
    if(experimental) {
      const all=[...experimental.strictCandidates,...experimental.oneOneCandidates,...experimental.additionalCandidates];
      if(all.some(l=>l.timeframeMinutes!==60 || l.confirmedAtMsc>cutoff || l.confirmedAtMsc<=l.extremeTimeMsc
        || !preparation.sourceSessions.includes(new Date(l.extremeTimeMsc).toISOString().slice(0,10))))
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','experimental'],message:'Candidato experimental fora da janela ou confirmado no futuro.'});
      const ids=new Set(preparation.candidates.map(l=>l.id));
      const oneIds=new Set(experimental.oneOneCandidates.map(l=>l.id));
      if(experimental.strictCandidates.some(l=>!ids.has(l.id)) || experimental.additionalCandidates.some(l=>!ids.has(l.id) || !oneIds.has(l.id) || experimental.baselineMatches[l.id]))
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','experimental'],message:'Comparacao deve preservar 2+2 e identificar apenas adicionais 1+1.'});
      if(experimental.evaluations.some(e=>(e.confirmedAtMsc!==null && e.confirmedAtMsc>cutoff)
        || !preparation.sourceSessions.includes(new Date(e.originMsc).toISOString().slice(0,10)))
        || experimental.regions.some(r=>r.low>r.high || r.high-r.low>experimental.regionWidthPoints || r.pivotIds.some(id=>!ids.has(id))
          || r.tests.some(t=>t.startMsc>t.endMsc || t.endMsc>=t.knownAtMsc || t.knownAtMsc>t.measuredThroughMsc || t.measuredThroughMsc>cutoff)))
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','experimental'],message:'Regiao ou reacao experimental utiliza dados futuros ou invalidos.'});
    }
    preparation.candidates.forEach((level,index)=>{
      const originDay=new Date(level.extremeTimeMsc).toISOString().slice(0,10);
      if(level.timeframeMinutes!==60 || !preparation.sourceSessions.includes(originDay)
        || originDay>=preparation.sessionDate || level.confirmedAtMsc>cutoff)
        ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','candidates',index],message:'Referencia H1 fora dos pregoes anteriores ou confirmada depois da abertura.'});
    });
    if(preparation.sourceSessions.length>3 || new Set(preparation.sourceSessions).size!==preparation.sourceSessions.length
      || preparation.sourceSessions.some(date=>date>=preparation.sessionDate))
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','sourceSessions'],message:'A preparacao deve usar ate tres pregoes anteriores distintos.'});
    const candidateIds=new Set(preparation.candidates.map(l=>l.id));
    const preparedIds=new Set(preparation.levels.map(l=>l.id));
    if(preparation.levels.some(l=>!candidateIds.has(l.id))
      || levels.filter(l=>l.timeframeMinutes===60).some(l=>!preparedIds.has(l.id)))
      ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','preparation','levels'],message:'A exibicao H1 deve corresponder ao plano preparado.'});
  }
  const prices = new Set<string>();
  levels.forEach((level, index) => {
    const path = ['structure', 'levels', index];
    const key = level.timeframeMinutes === 5 ? `M5:${level.id}` : `${level.timeframeMinutes}:${level.price}`;
    if (prices.has(key)) ctx.addIssue({ code: z.ZodIssueCode.custom, path: [...path, 'price'],
      message: `Nivel duplicado no timeframe ${level.timeframeMinutes}: ${level.price}.` });
    prices.add(key);
    if (level.confirmedAtMsc <= level.extremeTimeMsc) ctx.addIssue({ code: z.ZodIssueCode.custom,
      path: [...path, 'confirmedAtMsc'], message: `Confirmacao do nivel ${level.id} deve ocorrer depois do extremo.` });
    if (level.timeframeMinutes === 5 && preparation
      && new Date(level.extremeTimeMsc).toISOString().slice(0,10) !== preparation.sessionDate)
      ctx.addIssue({code:z.ZodIssueCode.custom,path:[...path,'extremeTimeMsc'],message:'Pivo M5 fora da sessao selecionada.'});
    const cutoff = level.timeframeMinutes === 60 && view.structure!.preparation
      ? Math.min(availableAt, view.structure!.preparation.sessionOpenMsc) : availableAt;
    if (level.confirmedAtMsc > cutoff) ctx.addIssue({ code: z.ZodIssueCode.custom,
      path: [...path, 'confirmedAtMsc'],
      message: `Nivel ${level.id} confirmado em ${level.confirmedAtMsc}, apos o instante disponivel ${cutoff}.` });
  });
});
export type CandleView = z.infer<typeof viewSchema>;
export type Config = { liveSymbol: string; timeframes: number[]; zone: string; sessions: { symbol: string; date: string }[] };
export async function config(signal: AbortSignal): Promise<Config> {
  return (await httpClient.get('/api/three-candles/config', { signal })).data;
}
export async function live(symbol: string, minutes: number, signal: AbortSignal, maxLevels = 6) {
  return viewSchema.parse((await httpClient.get('/api/three-candles/live', { params: { symbol, timeframeMinutes: minutes, maxLevels }, signal, timeout: 70_000 })).data);
}
export async function start(symbol: string, date: string, startTime: string, minutes: number, signal: AbortSignal, timeBasis: CandleTimeBasis = 'MT5_WALL_CLOCK', maxLevels = 6, progress?: (message: string) => void, h1DetectionMode: H1DetectionMode = 'STRICT_2_2', replayMode: ReplayMode = 'CLOSED_CANDLE') {
  progress?.('Verificando histórico local...');
  const status = (await httpClient.get('/api/three-candles/history/status', { params: { symbol, date }, signal })).data;
  if (!status.cached) {
    progress?.('Histórico local ausente ou incompleto. Consultando MetaTrader 5...');
    await httpClient.post('/api/three-candles/history/prepare', { symbol, date, timeframeMinutes: minutes }, { signal, timeout: 120_000 });
    progress?.('Histórico recuperado. Preparando Replay...');
  } else progress?.('Histórico local validado. Preparando Replay...');
  const view=viewSchema.parse((await httpClient.post('/api/three-candles/replays', { symbol, date, startTime, timeframeMinutes: minutes, timeBasis, maxLevels, h1DetectionMode, replayMode }, { signal, timeout: 120_000 })).data);
  if(replayMode==='PROGRESSIVE_M1' && view.replayMode!==replayMode) throw new Error('O backend nao ativou o Replay progressivo M1. Atualize-o e carregue novamente.');
  if(replayMode==='PROGRESSIVE_M1' && view.replayMode!==replayMode) throw new Error('O backend nao ativou o Replay progressivo M1. Atualize-o e carregue novamente.');
  if(h1DetectionMode==='EXPERIMENTAL_1_1' && view.structure?.preparation?.detectionMode!==h1DetectionMode)
    throw new Error('O backend não ativou o modo H1 experimental. Reinicie-o com a versão atual e carregue novamente.');
  return view;
}
export async function replay(id: string, signal: AbortSignal, maxLevels = 6) {
  return viewSchema.parse((await httpClient.get(`/api/three-candles/replays/${id}`, { signal, params: { maxLevels } })).data);
}
export async function command(id: string, action: string, speed?: number, maxLevels = 6) {
  return viewSchema.parse((await httpClient.post(`/api/three-candles/replays/${id}/commands`, { action, speed }, { params: { maxLevels } })).data);
}
export async function close(id: string) { await httpClient.delete(`/api/three-candles/replays/${id}`); }

/** Archive the existing display projection; this endpoint never computes grouping. */
export async function archiveM5VisualAudit(scope: string, symbol: string, tolerance: number, availableAtMsc: number,
  replacements: readonly import('../models/m5DisplayGroups').M5VisualReplacement[], signal: AbortSignal) {
  await httpClient.post('/api/three-candles/m5-visual-audit', {scope, symbol, tolerance, availableAtMsc, replacements}, {signal});
}
