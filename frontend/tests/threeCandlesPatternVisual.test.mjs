import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
import * as model from '../src/features/threecandles/models/threeCandlePatternAudit.ts';
import {patternDuplicationAudit,presentPatterns} from '../src/features/threecandles/models/patternPresentation.ts';
const compile=(path,require)=>{const exports={};runInNewContext(ts.transpileModule(readFileSync(new URL(path,import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,{exports,require,Map,Math});return exports;};
const {ThreeCandlePatternPrimitive}=compile('../src/features/threecandles/components/ThreeCandlePatternPrimitive.ts',()=>model);
const api=compile('../src/features/threecandles/api/threeCandlesApi.ts',n=>n==='zod'?{z}:{httpClient:{}});
const native=name=>JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/pattern-detector-v2/${name}.json`,import.meta.url),'utf8'));
test('duplication audit preserves every candidate and defines exact-region and candle-sequence grouping',()=>{
  const s=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/pattern-detector-v2/WINZ26-2026-10-30-study.json',import.meta.url),'utf8'));
  const before=JSON.stringify(s),a=patternDuplicationAudit(s.candidates);
  assert.equal(a.total,s.candidates.length);assert.ok(a.total>0);
  assert.equal(a.sequences,new Set(s.candidates.map(c=>[c.candle1Id,c.candle2Id??'?',c.candle3Id??'?'].join('|'))).size);
  assert.equal(a.reached,s.candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO').length);
  assert.equal(a.both,0);assert.equal(a.variants[0].total,0);assert.equal(a.variants[1].total,0);
  assert.equal(a.variants[2].total,s.candidates.filter(c=>c.recognizedAtMsc!=null).length);
  for(const mode of ['SELECTED','TRIGGERS','ALL'])presentPatterns(s.visual,s,mode,s.candidates[0].id);
  assert.equal(JSON.stringify(s),before);
});

test('native detector transport preserves causal stages and rejects future or retroactive trigger evidence',()=>{
  for(const name of ['0921','0926','0927','1113','1120','1121','1123','1140','1155']) {
    const result=api.viewSchema.safeParse(native(name));
    assert.equal(result.success,true,result.success?'':JSON.stringify(result.error.issues).slice(0,1200));
  }
  for(const mutate of [
    (c,v)=>c.events[0].atMsc=v.availableAtMsc+1,
    (c,v)=>c.region.availableAtMsc=v.availableAtMsc+1,
    c=>c.trigger.reachedAtMsc=c.trigger.placedAtMsc,
    c=>c.trigger.level+=100,
    c=>c.trigger.evidence.startTimeMsc=c.trigger.placedAtMsc-60000,
  ]) {
    const v=native('1155'),c=v.patternStudy.candidates.find(c=>c.trigger.state==='ALCANCADO_HIPOTETICO');
    assert.ok(c);mutate(c,v);assert.equal(api.viewSchema.safeParse(v).success,false);
  }
});

test('E is distinct, causal and deduplicated while underlying reference candidates remain intact',()=>{
  const v=native('1155'),c=v.patternStudy.candidates.find(c=>c.trigger.state==='ALCANCADO_HIPOTETICO');
  const s={...v.patternAudit,patterns:v.patternAudit.patterns.filter(p=>p.id===c.id)};
  const texts=[],ctx={save(){},restore(){},setLineDash(){},strokeRect(){},beginPath(){},arc(){},fill(){},stroke(){},fillText(t){texts.push(t);}};
  const p=new ThreeCandlePatternPrimitive();p.attached({chart:{timeScale:()=>({timeToCoordinate:()=>100,options:()=>({barSpacing:10})})},series:{priceToCoordinate:()=>100},requestUpdate(){}});
  const e={patternId:c.id,direction:c.direction,candleTimeMsc:c.candle3.candle.bucketStartTimeMsc,availableAtMsc:c.trigger.reachedAtMsc,level:c.trigger.level};
  p.set({snapshot:s,candles:v.candles,visible:true,numbers:true,highlights:true,executions:[e,e]});
  p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})});
  assert.deepEqual(texts,['1','2','3','E']);
  assert.ok(v.patternStudy.candidates.length>s.patterns.length);
});
const start=Date.parse('2026-10-30T09:00:00Z');
const observations=[0,1,2].map(i=>({candle:{symbol:'WINZ26',bucketStartTimeMsc:start+i*300000,open:100,high:110,low:90,close:i===1?95:105},timeframeMinutes:5,closed:true,availableAtMsc:start+(i+1)*300000}));
const stages=observations.map((observation,i)=>({number:i+1,role:['ALVO','NEGA','TESTA'][i],observation,referencePrice:observation.candle.close,availableAtMsc:observation.availableAtMsc,reason:'VISUAL FIXTURE ONLY; recognition criteria not supplied'}));
const snapshot={symbol:'WINZ26',timeframeMinutes:5,sessionDate:'2026-10-30',availableAtMsc:start+900000,rulesStatus:'DEFINED',criterionId:'VISUAL_FIXTURE_ONLY_NOT_RECOGNITION',patterns:[{id:'demo',direction:'BUY',state:'COMPLETE',stages}]};

test('evidence projection reveals only known consecutive stages and never guesses recognition from candles',()=>{
  for(let i=0;i<=3;i++)assert.equal(model.knownPatternStages(snapshot,start+i*300000).length,i);
  assert.equal(model.knownPatternStages({...snapshot,rulesStatus:'PENDING_DEFINITION'}).length,0);
  const malformed=structuredClone(snapshot);malformed.patterns[0].stages[1].number=3;
  assert.equal(model.knownPatternStages(malformed).length,1);
});
test('BUY/SELL outlines preserve candle bodies, badges stay outside wicks, all three controls and zoom are independent',()=>{
  for(const direction of ['BUY','SELL']) {
    const s=structuredClone(snapshot);s.patterns[0].direction=direction;
    const outlines=[],texts=[],fills=[];let zoom=1;
    const ctx={save(){},restore(){},setLineDash(){},strokeRect(...rect){outlines.push({rect,color:this.strokeStyle});},beginPath(){},arc(){},fill(){fills.push(this.fillStyle);},fillText(text,x,y){texts.push({text,x,y});}};
    const p=new ThreeCandlePatternPrimitive();
    p.attached({chart:{timeScale:()=>({timeToCoordinate:t=>(t-start/1000)/300*30*zoom+30,options:()=>({barSpacing:10*zoom})})},series:{priceToCoordinate:price=>200-price},requestUpdate(){}});
    const target={useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})};
    const original=JSON.stringify(observations);
    const draw=(flags={})=>{p.set({snapshot:s,candles:observations,visible:true,numbers:true,highlights:true,...flags});p.paneViews()[0].renderer().draw(target);};
    draw();assert.deepEqual(outlines.map(o=>o.color),['#1E40AF','#7DD3FC','#FB923C']);assert.deepEqual(texts.map(t=>t.text),['1','2','3']);
    assert.ok(texts.every(t=>direction==='BUY'?t.y>110:t.y<90));
    const count=outlines.length;draw({highlights:false});assert.equal(outlines.length,count);assert.equal(texts.length,6);
    draw({numbers:false});assert.equal(texts.length,6);assert.equal(outlines.length,6);
    draw({visible:false});assert.equal(texts.length,6);assert.equal(outlines.length,6);
    zoom=2;draw();assert.notEqual(outlines[1].rect[0],outlines[7].rect[0]);
    assert.equal(p.autoscaleInfo,undefined);assert.equal(JSON.stringify(observations),original);
    const future=observations.map(b=>({...b,availableAtMsc:s.availableAtMsc+1}));
    const n=texts.length;p.set({snapshot:s,candles:future,visible:true,numbers:true,highlights:true});p.paneViews()[0].renderer().draw(target);assert.equal(texts.length,n);
  }
});
test('transport requires causal observation evidence, criterion, stage sequence and the current symbol/cursor',()=>{
  const view={symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',availableAtMsc:snapshot.availableAtMsc,candles:observations,patternAudit:snapshot};
  assert.equal(api.viewSchema.safeParse(view).success,true);
  for(const mutate of [s=>s.symbol='WINV26',s=>s.availableAtMsc++,s=>s.criterionId=null,s=>s.rulesStatus='PENDING_DEFINITION',
    s=>s.patterns[0].stages[2].availableAtMsc++,s=>s.patterns[0].stages[1].role='TESTA',s=>s.patterns[0].stages[0].observation.availableAtMsc=s.availableAtMsc+1]) {
    const bad=structuredClone(view);mutate(bad.patternAudit);assert.equal(api.viewSchema.safeParse(bad).success,false);
  }
});


test('V2 keeps contact candidates unmarked until C2 and validates frozen context and fixed C3 reference',()=>{
 const early=native('0921');
 for(const c of early.patternStudy.candidates.filter(c=>c.recognizedAtMsc==null))
   assert.ok(!early.patternAudit.patterns.some(p=>p.id===c.id));
 const late=native('1155');
 assert.ok(late.patternStudy.candidates.some(c=>c.recognizedAtMsc!=null));
 for(const c of late.patternStudy.candidates.filter(c=>c.recognizedAtMsc!=null)) {
  const p=late.patternAudit.patterns.find(p=>p.id===c.id);
  assert.equal(p.stages[0].availableAtMsc,c.candle2.availableAtMsc);
  assert.equal(c.recognizedAtMsc,c.candle2.availableAtMsc);
  assert.equal(c.context.flow,c.direction==='BUY'?'DOWN':'UP');
  assert.ok(c.context.to.availableAtMsc<=c.candle1.candle.bucketStartTimeMsc);
 }
 for(const mutate of [c=>c.context.availableAtMsc=c.candle1.candle.bucketStartTimeMsc+1,
  c=>c.context.flow=c.direction==='BUY'?'UP':'DOWN',
  c=>c.context.to.candle.close=c.context.from.candle.close,
  c=>c.recognizedAtMsc=c.candle1.availableAtMsc,
  c=>c.trigger.referencePrice=c.candle3.candle.high+100,
  c=>c.events=c.events.filter(e=>e.kind!=='REJEICAO_OBSERVADA')]) {
   const v=native('1155');const c=v.patternStudy.candidates.find(c=>c.trigger.state==='ALCANCADO_HIPOTETICO');
   assert.ok(c);mutate(c);assert.equal(api.viewSchema.safeParse(v).success,false);
 }
});


test('V3 uses a causal confirmed Dow anchor, keeps structural outputs unchanged and shows every reached setup',()=>{
  const load=name=>JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/pattern-detector-v3/${name}.json`,import.meta.url),'utf8'));
  for(const name of ['0921','0926','0927','1113','1120','1121','1123','1140','1155']) {
    const view=load(name),old=native(name),result=api.viewSchema.safeParse(view);
    assert.equal(result.success,true,result.success?'':JSON.stringify(result.error.issues));
    for(const field of ['candles','structure','dow','microDow','dowFifty','microDowFifty','openingStructure'])
      assert.deepEqual(field==='structure'?Object.fromEntries(Object.entries(view[field]).filter(([k])=>k!=='earlyPivots')):view[field],old[field],`Structural output changed: ${field}`);
    const presented=presentPatterns(view.patternAudit,view.patternStudy,'SETUPS','');
    const ids=view.patternStudy.candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO').map(c=>c.id);
    assert.deepEqual(presented.snapshot.patterns.map(p=>p.id).sort(),ids.sort());
  }
  for(const mutate of [
    c=>c.context.anchor.confirmedAtMsc=c.context.availableAtMsc+1,
    c=>c.context.anchor.extremeTimeMsc+=300000,
    c=>c.context.anchor.price=c.direction==='BUY'?1:999999,
    c=>c.context.source='TWO_PRIOR_CLOSED_M5_CLOSES',
    c=>c.context.anchor=null,
  ]) {const view=load('1155'),c=view.patternStudy.candidates.find(c=>c.trigger.reachedAtMsc!=null);assert.ok(c);mutate(c);assert.equal(api.viewSchema.safeParse(view).success,false);}
});


test('October06 accepts partial native M1 coverage and preserves causally confirmed setups before later gaps',()=>{
  const load=name=>JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/pattern-detector-oct06/${name}.json`,import.meta.url),'utf8'));
  for(const name of ['1005','1030','view-after']) {
    const view=load(name),parsed=api.viewSchema.safeParse(view);
    assert.equal(parsed.success,true,parsed.success?'':JSON.stringify(parsed.error.issues));
    assert.equal(view.patternStudy.m1Status,name==='view-after'?'NATIVE_M1_GAPS_INDETERMINATE':'NATIVE_M1_COMPLETE_KNOWN_PREFIX');
    assert.ok(view.patternStudy.candidates.some(c=>c.trigger.reachedAtMsc!=null));
    for(const c of view.patternStudy.candidates)assert.ok(c.events.every(e=>e.atMsc<=view.availableAtMsc));
  }
});


test('native 2+1 evidence is available at C close, separate from confirmed Dow, and rejects future confirmation',()=>{
  const load=()=>JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/pattern-detector-v3/0926.json',import.meta.url),'utf8'));
  const view=load(),p=view.structure.earlyPivots.find(p=>p.status==='CANDIDATE_2_1');assert.ok(p);
  assert.equal(api.viewSchema.safeParse(view).success,true);
  assert.equal(p.availableAtMsc,p.rightC.availableAtMsc);assert.equal(p.rightD,null);
  assert.ok(!view.dow.pivots.some(d=>d.extreme.extremeTimeMsc===p.origin.candle.bucketStartTimeMsc&&d.extreme.type===p.type));
  for(const mutate of [
    (p,v)=>p.availableAtMsc=v.availableAtMsc+1,
    p=>p.rightC.closed=false,
    p=>p.origin.candle.bucketStartTimeMsc+=300000,
    p=>p.price+=10000,
    p=>{p.status='CONFIRMED_2_2';p.resolvedAtMsc=p.availableAtMsc+300000;},
  ]){const bad=load(),candidate=bad.structure.earlyPivots.find(p=>p.status==='CANDIDATE_2_1');mutate(candidate,bad);assert.equal(api.viewSchema.safeParse(bad).success,false);}
  const before=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/pattern-detector-v3/1120.json',import.meta.url),'utf8'));
  const monitoring=presentPatterns(before.patternAudit,before.patternStudy,'MONITORING','');
  const pending=before.patternStudy.candidates.find(c=>c.recognizedAtMsc===before.availableAtMsc&&c.candle3==null);assert.ok(pending);
  const visual=monitoring.snapshot.patterns.find(p=>p.id===pending.id);assert.ok(visual);assert.equal(visual.stages.length,2);
  assert.ok(!before.structure.earlyPivots?.some(p=>p.origin.candle.bucketStartTimeMsc===pending.candle1.candle.bucketStartTimeMsc));
  assert.ok(!monitoring.executions.some(e=>e.patternId===pending.id));
});


test('native September16 progressive replay loads opening gaps and resumes from the next intact M5',()=>{
  const load=name=>JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/progressive-gaps/${name}.json`,import.meta.url),'utf8'));
  const opening=Date.UTC(2026,8,16,9);
  for(const name of ['0900','0905','0906']) {
    const view=load(name),result=api.viewSchema.safeParse(view);
    assert.equal(result.success,true,result.success?'':JSON.stringify(result.error.issues));
    assert.equal(view.symbol,'WIN$N');assert.equal(view.replayMode,'PROGRESSIVE_M1');
    assert.deepEqual(view.missingM1Times,[opening,opening+60000]);
    assert.ok(view.candles.every(c=>c.availableAtMsc<=view.availableAtMsc));
  }
  assert.ok(load('0900').candles.every(c=>c.candle.bucketStartTimeMsc<opening));
  const close=load('0905').candles.at(-1);assert.equal(close.closed,true);assert.equal(close.availableAtMsc,opening+300000);
  const next=load('0906').candles.at(-1);assert.equal(next.closed,false);assert.equal(next.candle.bucketStartTimeMsc,opening+300000);
});


test('V4 validates native qualified wicks and refuses weak, altered, future or retroactive entry evidence',()=>{
  const load=()=>JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/pattern-wick-v4/oct06/view-after.json',import.meta.url),'utf8'));
  const view=load(),parsed=api.viewSchema.safeParse(view);
  assert.equal(parsed.success,true,parsed.success?'':JSON.stringify(parsed.error.issues));
  assert.equal(view.patternStudy.parameters.minRejectionTicks,6);
  const reached=view.patternStudy.candidates.filter(c=>c.trigger.reachedAtMsc!=null);assert.ok(reached.length>0);
  assert.ok(reached.every(c=>c.rejectionWick.points>=30&&c.rejectionWick.availableAtMsc===c.trigger.placedAtMsc&&c.trigger.reachedAtMsc>c.trigger.placedAtMsc));
  for(const mutate of [
    c=>c.rejectionWick.points=29,
    c=>c.rejectionWick.ticks=5,
    c=>c.rejectionWick.minimumPoints=150,
    (c,v)=>c.rejectionWick.availableAtMsc=v.availableAtMsc+1,
    c=>c.rejectionWick=null,
    c=>c.trigger.placedAtMsc-=60000,
  ]){const bad=load(),c=bad.patternStudy.candidates.find(c=>c.trigger.reachedAtMsc!=null);mutate(c,bad);assert.equal(api.viewSchema.safeParse(bad).success,false);}
});
