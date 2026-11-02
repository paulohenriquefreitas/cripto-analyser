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
const patternStageSchema=z.object({number:z.union([z.literal(1),z.literal(2),z.literal(3)]),role:z.enum(['ALVO','NEGA','TESTA']),
  observation:updateSchema.extend({timeframeMinutes:z.literal(5)}),referencePrice:z.number().finite().positive(),
  availableAtMsc:z.number().int().safe(),reason:z.string().trim().min(1)});
const patternAuditSchema=z.object({symbol:z.string(),timeframeMinutes:z.literal(5),sessionDate:z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  availableAtMsc:z.number().int().safe(),rulesStatus:z.enum(['PENDING_DEFINITION','DEFINED']),criterionId:z.string().trim().min(1).nullable(),
  patterns:z.array(z.object({id:z.string().min(1),direction:z.enum(['BUY','SELL']),state:z.enum(['CANDLE_1','CANDLES_1_2','COMPLETE']),
    stages:z.array(patternStageSchema).min(1).max(3)}))}).superRefine((s,ctx)=>{
    const bad=s.rulesStatus==='PENDING_DEFINITION'?(s.patterns.length!==0||s.criterionId!==null)
      :s.criterionId===null || new Set(s.patterns.map(p=>p.id)).size!==s.patterns.length || s.patterns.some(p=>
        p.state!==['CANDLE_1','CANDLES_1_2','COMPLETE'][p.stages.length-1] || p.stages.some((v,i)=>{
          const b=v.observation,t=b.candle.bucketStartTimeMsc,previous=p.stages[i-1];
          return v.number!==i+1 || v.role!==['ALVO','NEGA','TESTA'][i] || b.candle.symbol!==s.symbol
            || new Date(t).toISOString().slice(0,10)!==s.sessionDate || t%300000!==0
            || b.availableAtMsc<t+(b.closed?300000:0) || v.availableAtMsc<b.availableAtMsc || v.availableAtMsc>s.availableAtMsc
            || (previous && (t<=previous.observation.candle.bucketStartTimeMsc || v.availableAtMsc<previous.availableAtMsc));
        }));
    if(bad)ctx.addIssue({code:z.ZodIssueCode.custom,message:'Etapas visuais futuras ou sem evidência causal válida.'});
  });
const patternMinuteSchema=z.object({startTimeMsc:z.number().int().safe(),open:z.number().finite(),high:z.number().finite(),low:z.number().finite(),close:z.number().finite()});
const patternRegionSchema=z.object({id:z.string(),source:z.string(),low:z.number().positive(),high:z.number().positive(),availableAtMsc:z.number().int().safe(),provenance:z.string()});
const patternTriggerSchema=z.object({state:z.enum(['NAO_POSICIONADO','AGUARDANDO','ALCANCADO_HIPOTETICO','INDETERMINADO','CANCELADO','EXPIRADO']),
  referencePrice:z.number().nullable(),level:z.number().nullable(),referenceKnownAtMsc:z.number().int().safe().nullable(),placedAtMsc:z.number().int().safe().nullable(),
  reachedAtMsc:z.number().int().safe().nullable(),evidence:patternMinuteSchema.nullable(),reason:z.string()});
const patternStudySchema=z.object({symbol:z.string(),sessionDate:z.string(),availableAtMsc:z.number().int().safe(),criterionId:z.enum(['EXPERIMENTAL_ALVO_NEGA_AB_TESTA_M1_V1','CONTEXT_CLOSE_NEGATION_TEST_RETURN_M1_V2','DOW_LEG_CLOSE_NEGATION_TEST_RETURN_M1_V3','DOW_LEG_MINIMUM_WICK_TEST_RETURN_M1_V4']),
  parameters:z.object({contactTolerancePoints:z.number().nonnegative(),orderOffsetPoints:z.number().positive(),minRejectionTicks:z.number().nonnegative().optional()}),status:z.literal('HIPOTESE_OPERACIONAL_EM_VALIDACAO'),m1Status:z.string(),
  candidates:z.array(z.object({id:z.string(),symbol:z.string(),sessionDate:z.string(),direction:z.enum(['BUY','SELL']),region:patternRegionSchema,state:z.string(),
    negationVariants:z.array(z.enum(['A_FALHA_RENOVACAO','B_FALSO_ROMPIMENTO','FECHAMENTO_NAO_CONTINUA_C1'])),negationComparedExtreme:z.number().nullable(),
    candle1:updateSchema.extend({timeframeMinutes:z.literal(5)}),candle2:updateSchema.extend({timeframeMinutes:z.literal(5)}).nullable(),candle3:updateSchema.extend({timeframeMinutes:z.literal(5)}).nullable(),
    candle1Id:z.string(),candle2Id:z.string().nullable(),candle3Id:z.string().nullable(),testLow:z.number().nullable(),testHigh:z.number().nullable(),testState:z.string(),trigger:patternTriggerSchema,
    events:z.array(z.object({atMsc:z.number().int().safe(),kind:z.string(),reason:z.string(),minute:patternMinuteSchema.nullable(),comparedPrice:z.number().nullable()})),validationStatus:z.literal('NAO_VALIDADO'),recognizedAtMsc:z.number().int().safe().nullable().optional(),
    rejectionWick:z.object({points:z.number().nonnegative(),ticks:z.number().nonnegative(),minimumPoints:z.number().nonnegative(),availableAtMsc:z.number().int().safe(),observation:updateSchema}).nullable().optional(),
    context:z.object({flow:z.enum(['UP','DOWN','UNDEFINED']),principalState:z.string(),microState:z.string(),source:z.string(),availableAtMsc:z.number().int().safe(),from:updateSchema.nullable(),to:updateSchema.nullable(),anchor:levelSchema.nullable().optional()}).optional()})),
  rejectedContacts:z.array(z.object({atMsc:z.number().int().safe(),candleTimeMsc:z.number().int().safe(),referenceId:z.string(),reason:z.string()})),visual:patternAuditSchema,
}).superRefine((s,ctx)=>{
  const bad=s.visual.availableAtMsc!==s.availableAtMsc || s.visual.symbol!==s.symbol
    || s.rejectedContacts.some(r=>r.atMsc>s.availableAtMsc)
    || s.candidates.some(c=>{
      const p=s.visual.patterns.find(p=>p.id===c.id),trigger=c.trigger,first=c.events[0];
      const v4=s.criterionId==='DOW_LEG_MINIMUM_WICK_TEST_RETURN_M1_V4';
      const v3=v4||s.criterionId==='DOW_LEG_CLOSE_NEGATION_TEST_RETURN_M1_V3';
      const v2=v3||s.criterionId==='CONTEXT_CLOSE_NEGATION_TEST_RETURN_M1_V2';
      const recognized=c.recognizedAtMsc!=null;
      const context=c.context;
      const invalidV2=v2&&(!context||context.availableAtMsc>c.candle1.candle.bucketStartTimeMsc
        ||context.flow!==(c.direction==='BUY'?'DOWN':'UP')
        ||!context.from||!context.to
        ||(v3 ? !context.anchor||!['MICRO_DOW_DEVELOPING_LEG','PRINCIPAL_DOW_DEVELOPING_LEG'].includes(context.source)
          ||context.anchor.confirmedAtMsc>context.availableAtMsc
          ||context.anchor.extremeTimeMsc!==context.from?.candle.bucketStartTimeMsc
          ||context.to?.candle.bucketStartTimeMsc!==context.availableAtMsc-300000
          ||context.flow!==(context.to!.candle.close>context.anchor.price?'UP':context.to!.candle.close<context.anchor.price?'DOWN':'UNDEFINED')
          :context.source!=='TWO_PRIOR_CLOSED_M5_CLOSES'||(context.from&&context.to&&(context.to.candle.bucketStartTimeMsc!==context.from.candle.bucketStartTimeMsc+300000
            ||context.flow!==(context.to.candle.close>context.from.candle.close?'UP':context.to.candle.close<context.from.candle.close?'DOWN':'UNDEFINED'))))
        ||[context.from,context.to].some(b=>b&&(b.availableAtMsc>context.availableAtMsc||b.candle.bucketStartTimeMsc>=c.candle1.candle.bucketStartTimeMsc||!b.closed))
        ||(recognized? !c.candle2||c.recognizedAtMsc!==c.candle2.availableAtMsc||!p||p.stages.length<2
          ||p.stages[0].availableAtMsc!==c.recognizedAtMsc
          ||(c.direction==='BUY'?c.candle2.candle.close<c.candle1.candle.close:c.candle2.candle.close>c.candle1.candle.close)
          ||c.negationComparedExtreme!==c.candle1.candle.close:!!p));
      const wick=c.rejectionWick,minimum=(s.parameters.minRejectionTicks??0)*5;
      const invalidWick=v4&&(s.parameters.minRejectionTicks==null
        ||(trigger.placedAtMsc!=null&&(!wick||wick.availableAtMsc!==trigger.placedAtMsc))
        ||!!wick&&(wick.availableAtMsc>s.availableAtMsc||wick.observation.availableAtMsc!==wick.availableAtMsc
          ||wick.observation.timeframeMinutes!==5||wick.observation.closed||wick.observation.candle.symbol!==s.symbol
          ||wick.observation.candle.bucketStartTimeMsc!==c.candle3?.candle.bucketStartTimeMsc
          ||wick.minimumPoints!==minimum||wick.points<minimum||wick.ticks!==wick.points/5
          ||wick.points!==(c.direction==='BUY'?Math.min(wick.observation.candle.open,wick.observation.candle.close)-wick.observation.candle.low
            :wick.observation.candle.high-Math.max(wick.observation.candle.open,wick.observation.candle.close))
          ||!c.events.some(e=>e.kind==='PAVIO_REJEICAO_QUALIFICADO'&&e.atMsc===wick.availableAtMsc&&e.comparedPrice===wick.points)));
      return invalidWick||c.symbol!==s.symbol || c.sessionDate!==s.sessionDate || (!v2&&!p) || (p&&p.direction!==c.direction) || !first || invalidV2
        || c.region.high<c.region.low || c.region.availableAtMsc>(first.minute?.startTimeMsc??c.candle1.candle.bucketStartTimeMsc)
        || [c.candle1,c.candle2,c.candle3].some((b,i)=>b && (b.availableAtMsc>s.availableAtMsc || b.candle.symbol!==s.symbol
          || [c.candle1Id,c.candle2Id,c.candle3Id][i]!==`${s.symbol}:5:${b.candle.bucketStartTimeMsc}`))
        || (c.candle2!=null && !c.candle2.closed) || c.events.some(e=>e.atMsc>s.availableAtMsc || (e.minute && e.minute.startTimeMsc+60000>e.atMsc))
        || (trigger.placedAtMsc!=null && (trigger.referenceKnownAtMsc==null || trigger.referenceKnownAtMsc>trigger.placedAtMsc || trigger.placedAtMsc>s.availableAtMsc
          || !p || p.stages.length<3
          || (v2 ? trigger.referenceKnownAtMsc!==p.stages[2].observation.candle.bucketStartTimeMsc+60000
            || trigger.referencePrice!==p.stages[2].observation.candle.open
            || (v4?!c.events.some(e=>e.kind==='ORDEM_HIPOTETICA_DISPONIVEL'&&e.atMsc===trigger.placedAtMsc):trigger.placedAtMsc!==p.stages[2].availableAtMsc)
            || !(c.direction==='BUY'?p.stages[2].observation.candle.low<p.stages[2].observation.candle.open:p.stages[2].observation.candle.high>p.stages[2].observation.candle.open)
            : trigger.referenceKnownAtMsc!==p.stages[2].availableAtMsc
              || trigger.referencePrice!==(c.direction==='BUY'?p.stages[2].observation.candle.high:p.stages[2].observation.candle.low))
          || trigger.referencePrice==null || trigger.level!==(c.direction==='BUY'?trigger.referencePrice+s.parameters.orderOffsetPoints:trigger.referencePrice-s.parameters.orderOffsetPoints)))
        || (trigger.reachedAtMsc!=null && (trigger.state!=='ALCANCADO_HIPOTETICO' || trigger.placedAtMsc==null || trigger.reachedAtMsc<=trigger.placedAtMsc
          || (v2&&(!c.events.some(e=>e.kind==='REJEICAO_OBSERVADA'&&e.atMsc<=trigger.reachedAtMsc!)||c.testState!=='REJEICAO_OBSERVADA'))
          || trigger.reachedAtMsc>s.availableAtMsc || !trigger.evidence || trigger.evidence.startTimeMsc<trigger.placedAtMsc
          || trigger.evidence.startTimeMsc+60000!==trigger.reachedAtMsc || trigger.level==null
          || !(c.direction==='BUY'?trigger.evidence.open<trigger.level&&trigger.evidence.high>=trigger.level:trigger.evidence.open>trigger.level&&trigger.evidence.low<=trigger.level)));
    });
  if(bad)ctx.addIssue({code:z.ZodIssueCode.custom,message:'Estudo experimental contém evidência futura ou gatilho retroativo.'});
});
export type PatternStudySnapshot=z.infer<typeof patternStudySchema>;
const chopReadingSchema=z.object({period:z.union([z.literal(7),z.literal(14),z.literal(21)]),value:z.number().finite().nullable(),delta:z.number().finite().nullable(),slope:z.enum(['UNAVAILABLE','RISING','FALLING','FLAT']),regime:z.enum(['UNAVAILABLE','DIRECTIONAL','CHOPPY','INTERMEDIATE']),status:z.enum(['VALID','INSUFFICIENT_HISTORY','ZERO_RANGE','NON_FINITE'])});
const chopPointSchema=z.object({candleTimeMsc:z.number().int().safe(),availableAtMsc:z.number().int().safe(),provisional:z.boolean(),open:z.number().finite(),high:z.number().finite(),low:z.number().finite(),close:z.number().finite(),displacement:z.number().finite().nullable(),priceDirection:z.enum(['UNAVAILABLE','UP','DOWN','FLAT']),observedSessionHigh:z.number().finite(),observedSessionLow:z.number().finite(),readings:z.array(chopReadingSchema).length(3)});
const chopSchema=z.object({symbol:z.string(),availableAtMsc:z.number().int().safe(),policy:z.enum(['CONTINUOUS_AVAILABLE_M5_INCLUDES_OVERNIGHT_TR','SESSION_ONLY_N_PLUS_1']),deltaBasis:z.literal('PREVIOUS_CONFIRMED_M5'),confirmed:z.array(chopPointSchema),provisional:chopPointSchema.nullable()}).superRefine((s,ctx)=>{
  const points=[...s.confirmed,...(s.provisional?[s.provisional]:[])];
  if(s.confirmed.some(p=>p.provisional||p.availableAtMsc<p.candleTimeMsc+300000)||s.provisional&&!s.provisional.provisional||points.some((p,i)=>p.availableAtMsc>s.availableAtMsc||p.availableAtMsc<p.candleTimeMsc||i>0&&p.candleTimeMsc<=points[i-1].candleTimeMsc||new Set(p.readings.map(r=>r.period)).size!==3||p.readings.some(r=>(r.status==='VALID')!==(r.value!=null))))ctx.addIssue({code:z.ZodIssueCode.custom,message:'CHOP com evidência futura, inválida ou confirmação antecipada.'});
});
const dmiValue=z.number().finite().min(0).max(100).nullable();
const dmiPointSchema=z.object({candleTimeMsc:z.number().int().safe(),availableAtMsc:z.number().int().safe(),provisional:z.boolean(),plusDi:dmiValue,minusDi:dmiValue,adx:dmiValue,warmupPlusDi:dmiValue.optional(),warmupMinusDi:dmiValue.optional(),regime:z.enum(['UNAVAILABLE','FLAT','INDEFINITE','UP','DOWN'])});
const dmiSchema=z.object({symbol:z.string(),availableAtMsc:z.number().int().safe(),period:z.literal(14),confirmed:z.array(dmiPointSchema),provisional:dmiPointSchema.nullable()}).superRefine((s,ctx)=>{
  const sessionStart=Math.floor(s.availableAtMsc/86400000)*86400000+9*3600000;
  if([...s.confirmed,...(s.provisional?[s.provisional]:[])].some(p=>p.candleTimeMsc<sessionStart))
    ctx.addIssue({code:z.ZodIssueCode.custom,message:'DMI deve conter apenas candles da sessao atual a partir das 09:00 do feed.'});
  const points=[...s.confirmed,...(s.provisional?[s.provisional]:[])];
  if(s.confirmed.some(p=>p.provisional||p.availableAtMsc<p.candleTimeMsc+300000)||s.provisional&&!s.provisional.provisional||points.some((p,i)=>p.availableAtMsc>s.availableAtMsc||p.availableAtMsc<p.candleTimeMsc||i>0&&p.candleTimeMsc<=points[i-1].candleTimeMsc))ctx.addIssue({code:z.ZodIssueCode.custom,message:'DMI com evid?ncia futura ou confirma??o antecipada.'});
});
const earlyPivotSchema=z.object({id:z.string(),type:z.enum(['TOP','BOTTOM']),price:z.number().positive(),
  leftA:updateSchema,leftB:updateSchema,origin:updateSchema,rightC:updateSchema,availableAtMsc:z.number().int().safe(),
  status:z.enum(['CANDIDATE_2_1','CONFIRMED_2_2','INVALIDATED','INCONCLUSIVE_GAP']),rightD:updateSchema.nullable(),resolvedAtMsc:z.number().int().safe().nullable(),reason:z.string()});
export type EarlyDowPivot=z.infer<typeof earlyPivotSchema>;
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
  patternAudit:patternAuditSchema.nullable().optional(),
  patternStudy:patternStudySchema.nullable().optional(),
  chop:chopSchema.nullable().optional(),
  dmi:dmiSchema.nullable().optional(),
  structure: z.object({
    levels: z.array(levelSchema),
    confirmedPivots: z.array(levelSchema).optional(),
    earlyPivots:z.array(earlyPivotSchema).optional(),
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
  if(view.dmi&&(view.timeframeMinutes!==5||view.dmi.symbol!==view.symbol||view.dmi.availableAtMsc!==view.availableAtMsc))ctx.addIssue({code:z.ZodIssueCode.custom,path:['dmi'],message:'DMI fora do ativo ou instante do Replay.'});
  if(view.chop&&(view.timeframeMinutes!==5||view.chop.symbol!==view.symbol||view.chop.availableAtMsc!==view.availableAtMsc))ctx.addIssue({code:z.ZodIssueCode.custom,path:['chop'],message:'CHOP fora do ativo ou instante do Replay.'});
  if(view.patternStudy && (view.timeframeMinutes!==5 || view.patternStudy.symbol!==view.symbol || view.patternStudy.availableAtMsc!==view.availableAtMsc
    || view.patternAudit?.criterionId!==view.patternStudy.criterionId || view.patternAudit?.patterns.length!==view.patternStudy.visual.patterns.length))
    ctx.addIssue({code:z.ZodIssueCode.custom,path:['patternStudy'],message:'Detector experimental fora do ativo, instante ou auditoria visual.'});
  if(view.patternAudit && (view.timeframeMinutes!==5 || view.patternAudit.symbol!==view.symbol
    || view.patternAudit.availableAtMsc!==view.availableAtMsc))
    ctx.addIssue({code:z.ZodIssueCode.custom,path:['patternAudit'],message:'Auditoria do padrão fora do ativo ou instante atual.'});
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
  if(view.structure.earlyPivots?.some(p=>{
    const group=[p.leftA,p.leftB,p.origin,p.rightC],top=p.type==='TOP';
    return view.timeframeMinutes!==5||p.availableAtMsc>availableAt||p.availableAtMsc!==p.rightC.availableAtMsc
      ||group.some((b,i)=>!b.closed||b.timeframeMinutes!==5||b.candle.symbol!==view.symbol||b.availableAtMsc>p.availableAtMsc||b.availableAtMsc<b.candle.bucketStartTimeMsc+300000
        ||Math.floor(b.candle.bucketStartTimeMsc/86400000)!==Math.floor(p.origin.candle.bucketStartTimeMsc/86400000)
        ||i>0&&b.candle.bucketStartTimeMsc!==group[i-1].candle.bucketStartTimeMsc+300000)
      ||p.price!==(top?p.origin.candle.high:p.origin.candle.low)
      ||[p.leftA,p.leftB,p.rightC].some(b=>top?p.price<=b.candle.high:p.price>=b.candle.low)
      ||(p.status==='CANDIDATE_2_1'?(p.rightD!==null||p.resolvedAtMsc!==null):p.resolvedAtMsc==null||p.resolvedAtMsc>availableAt||p.resolvedAtMsc<p.availableAtMsc)
      ||(p.status==='INCONCLUSIVE_GAP'?p.rightD!==null:p.status!=='CANDIDATE_2_1'&&(!p.rightD||!p.rightD.closed||p.rightD.timeframeMinutes!==5
        ||p.rightD.candle.symbol!==view.symbol||p.rightD.candle.bucketStartTimeMsc!==p.rightC.candle.bucketStartTimeMsc+300000
        ||p.rightD.availableAtMsc!==p.resolvedAtMsc
        ||(p.status==='CONFIRMED_2_2')!==(top?p.price>p.rightD.candle.high:p.price<p.rightD.candle.low)));
  }))ctx.addIssue({code:z.ZodIssueCode.custom,path:['structure','earlyPivots'],message:'Candidato 2+1 invalido ou com evidencia futura.'});
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
