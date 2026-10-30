import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { z } from 'zod';
import * as m5Groups from '../src/features/threecandles/models/m5DisplayGroups.ts';

function controller() {
  const source=readFileSync(new URL('../src/features/threecandles/models/candleChartController.ts',import.meta.url),'utf8');
  const { outputText }=ts.transpileModule(source,{ compilerOptions: { target: ts.ScriptTarget.ES2022,module: ts.ModuleKind.CommonJS } });
  const exports={}; runInNewContext(outputText,{ exports, require: () => m5Groups });
  return exports.createCandleChartController();
}
const bar=(time,close=100) => ({ candle:{ symbol:'WINZ26',bucketStartTimeMsc:time*1000,open:100,high:110,low:90,close }, timeframeMinutes:5,closed:true,availableAtMsc:time*1000+300000 });
const view=(bars,symbol='WINZ26',timeframeMinutes=5) => ({ symbol,timeframeMinutes,status:'PAUSED',candles:bars });

test('experimental audit accepts native data and rejects future candidates and reactions',()=>{
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const exports={};runInNewContext(outputText,{exports,Set,Date,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const fixture=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesExperimentalH1.json',import.meta.url),'utf8'));
  assert.equal(exports.viewSchema.safeParse(fixture).success,false,'old M5 session must be rejected');
  fixture.structure.levels=fixture.structure.levels.filter(l=>l.timeframeMinutes===60);
  const parsed=exports.viewSchema.safeParse(fixture);assert.equal(parsed.success,true,parsed.error?.message);
  assert.equal(parsed.data.structure.preparation.experimental.additionalCandidates.length,4);
  const late=structuredClone(fixture);late.structure.preparation.experimental.oneOneCandidates[0].confirmedAtMsc=fixture.availableAtMsc+1;
  assert.equal(exports.viewSchema.safeParse(late).success,false);
  const reaction=structuredClone(fixture);
  reaction.structure.preparation.experimental.regions.find(r=>r.tests.length).tests[0].measuredThroughMsc=fixture.availableAtMsc+1;
  assert.equal(exports.viewSchema.safeParse(reaction).success,false);
});

test('2+2 and additional 1+1 chart visibility is independent without resetting replay',()=>{
  const fixture=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesExperimentalH1.json',import.meta.url),'utf8'));
  const c=controller(),received=[];let histories=0;
  c.attachChart({setHistory(){histories++;},updateLast(){},setStructureLevels:l=>received.push(l)});
  c.apply(fixture);assert.equal(received.at(-1).length,13);
  c.setH1ComparisonVisibility(true,false);assert.equal(received.at(-1).length,9);
  c.setH1ComparisonVisibility(false,true);assert.equal(received.at(-1).length,4);
  c.setH1ComparisonVisibility(false,false);assert.equal(received.at(-1).length,0);
  c.setH1ComparisonVisibility(true,true);assert.equal(received.at(-1).length,13);
  assert.equal(histories,2);
  c.setH1ComparisonVisibility(false,false);
  c.apply({...fixture,structure:{...fixture.structure,levels:fixture.structure.preparation.experimental.strictCandidates,preparation:undefined}});
  assert.equal(received.at(-1).length,9,'experimental toggles must not hide H1 in normal mode');
});

test('transport prepares missing MT5 history before creating replay, and skips preparation on cache hit',async()=>{
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const response=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesPreparedH1.json',import.meta.url),'utf8'));
  for(const cached of [false,true]) {
    const calls=[],messages=[],exports={};
    const httpClient={get:async path=>{calls.push(path);return {data:{cached}};},
      post:async path=>{calls.push(path);return {data:path.endsWith('/replays')?response:{}};}};
    runInNewContext(outputText,{exports,Set,Date,require:name=>name==='zod'?{z}:{httpClient}});
    await exports.start('WIN$N','2026-10-16','09:00',5,undefined,'MT5_WALL_CLOCK',6,m=>messages.push(m));
    assert.deepEqual(calls,cached?['/api/three-candles/history/status','/api/three-candles/replays']:
      ['/api/three-candles/history/status','/api/three-candles/history/prepare','/api/three-candles/replays']);
    assert.equal(messages.some(m=>m.includes('Consultando MetaTrader 5')),!cached);
    assert.ok(messages.at(-1).includes('Preparando Replay'));
  }
});

test('replay focuses opening once and preserves user viewport during advancement', () => {
  const c=controller(),focused=[],histories=[];
  c.attachChart({setHistory:b=>histories.push(b),updateLast(){},focusTime:t=>focused.push(t)});
  const replay={...view([bar(6000)]),id:'replay',structure:{levels:[],preparation:{sessionOpenMsc:6300000}}};
  c.apply(replay);c.apply({...replay,candles:[bar(6000),bar(6300)]});
  assert.deepEqual(focused,[6300]);
  assert.equal(histories.at(-1)[0].time,6000);
  c.attachChart({setHistory(){},updateLast(){},focusTime:t=>focused.push(t)});
  assert.deepEqual(focused,[6300,6300]);
  c.reset();c.apply(replay);assert.deepEqual(focused,[6300,6300,6300]);
});
test('same candle and next candle update incrementally; identity changes reset safely', () => {
  const c=controller(); const histories=[],updates=[];
  c.attachChart({ setHistory:v=>histories.push(v),updateLast:v=>updates.push(v) });
  c.apply(view([bar(6000)]));
  c.apply(view([bar(6000,101)]));
  c.apply(view([bar(6000,101),bar(6300)]));
  assert.equal(updates.length,2); assert.equal(histories.length,2);
  c.apply(view([bar(6000)],'WINV26')); assert.equal(histories.length,3);
  c.apply(view([bar(6300)],'WINV26',15)); assert.equal(histories.length,4);
});
test('history remount and reset expose only received candles', () => {
  const c=controller(); c.apply(view([bar(6000)])); const values=[];
  const detach=c.attachChart({ setHistory:v=>values.push(v),updateLast(){} });
  assert.equal(values[0].length,1); detach();
  c.apply(view([bar(6000),bar(6300)])); assert.equal(values.length,1);
  c.attachChart({ setHistory:v=>values.push(v),updateLast(){} }); assert.equal(values.at(-1).length,2);
  c.reset(); assert.equal(values.at(-1).length,0);
});
test('rejects duplicate, reversed and misaligned candle times', () => {
  const c=controller();
  for(const bars of [[bar(6000),bar(6000)],[bar(6300),bar(6000)],[bar(6001)]]) assert.throws(()=>c.apply(view(bars)));
});


test('backend levels survive chart remount and are cleared on reset and timeframe change', () => {
  const c=controller(); c.setVisibility(true,true); const received=[];
  const level={id:'x',type:'TOP',price:110,extremeTimeMsc:6000000,confirmedAtMsc:7500000};
  c.attachChart({setHistory(){},updateLast(){},setStructureLevels:l=>received.push(l)});
  c.apply({...view([bar(7500)]),structure:{levels:[level]}});
  assert.equal(received.at(-1)[0].id,'x');
  c.attachChart({setHistory(){},updateLast(){},setStructureLevels:l=>received.push(l)});
  assert.equal(received.at(-1)[0].id,'x');
  c.apply({...view([bar(8100)],'WINZ26',15),structure:{levels:[level]}});
  assert.equal(received.at(-1).length,0);
  c.reset(); assert.equal(received.at(-1).length,0);
});

test('transport rejects future structure and allows distinct M5 pivots at the same price', () => {
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const { outputText }=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const exports={};runInNewContext(outputText,{exports,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const level={id:'x',type:'TOP',price:110,extremeTimeMsc:6000000,confirmedAtMsc:7500000,relevance:null,
    region:{high:110,low:90,midpoint:100,basis:'PIVOT_CANDLE'}};
  const structure={levels:[level],totalActive:1,maxLevels:6,supported:true,selection:'NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED'};
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure}).success,true);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,levels:[{...level,confirmedAtMsc:8000000}]}}).success,false);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,levels:[level,{...level,id:'y'}]}}).success,true);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,levels:[level,level]}}).success,false);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,maxLevels:1,levels:Array.from({length:30},(_,i)=>({...level,id:String(i)}))}}).success,true);
});

test('a hidden old broken level returns from the server without resetting candles', () => {
  const c=controller(); c.setVisibility(true,true); const received=[]; let histories=0;
  c.attachChart({setHistory(){histories++;},updateLast(){},setStructureLevels:l=>received.push(l)});
  const old={id:'old',type:'TOP',price:120,extremeTimeMsc:6000000,confirmedAtMsc:7500000,state:'BROKEN',brokenAtMsc:7800000};
  const near={...old,id:'near',type:'BOTTOM',price:80};
  const first=bar(7500);
  c.apply({...view([first]),structure:{levels:[old]}});
  c.apply({...view([first,bar(7800,81)]),structure:{levels:[near]}});
  assert.equal(received.at(-1).some(l=>l.id==='old'),false);
  c.apply({...view([first,bar(7800,81),bar(8100,109)]),structure:{levels:[old]}});
  assert.equal(received.at(-1)[0].id,'old');
  assert.equal(received.at(-1)[0].state,'BROKEN');
  assert.equal(histories,2);
});

test('M5 and H1 visibility is independent and never discards received levels', () => {
  const c=controller(),received=[];
  c.attachChart({setHistory(){},updateLast(){},setStructureLevels:l=>received.push(l)});
  const level={id:'m5',type:'TOP',price:110,extremeTimeMsc:6000000,confirmedAtMsc:7500000,timeframeMinutes:5};
  c.apply({...view([bar(7500)]),structure:{levels:[level,{...level,id:'h1',timeframeMinutes:60}]}});
  assert.equal(received.at(-1).length,1);
  assert.equal(received.at(-1)[0].id,'h1');
  c.setVisibility(true,true); assert.equal(received.at(-1).length,2);
  c.setVisibility(false,true);assert.equal(received.at(-1)[0].id,'h1');
  c.setVisibility(false,false);assert.equal(received.at(-1).length,0);
  c.apply({...view([bar(7500),bar(7800)]),structure:{levels:[level,{...level,id:'h1',timeframeMinutes:60}]}});
  assert.equal(received.at(-1).length,0);
  c.setVisibility(true,false);assert.equal(received.at(-1)[0].id,'m5');
  c.setVisibility(true,true);assert.equal(received.at(-1).length,2);
  c.attachChart({setHistory(){},updateLast(){},setStructureLevels:l=>received.push(l)});
  assert.equal(received.at(-1).length,2);
});

test('transport preserves equal prices across timeframes with independent limits and rejects future H1', () => {
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const exports={};runInNewContext(outputText,{exports,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const m5={id:'m5',type:'TOP',price:110,extremeTimeMsc:6000000,confirmedAtMsc:7500000,timeframeMinutes:5,relevance:'PROXIMITY',state:'CONFIRMED',brokenAtMsc:null,
    region:{high:110,low:90,midpoint:100,basis:'PIVOT_CANDLE'}};
  const h1={...m5,id:'h1',timeframeMinutes:60};
  const structure={levels:[m5,h1],totalActive:2,maxLevels:1,supported:true,selection:'NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED'};
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure}).success,true);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,levels:[m5,{...h1,confirmedAtMsc:9000000}]}}).success,false);
  assert.equal(exports.viewSchema.safeParse({...view([bar(7500)]),structure:{...structure,levels:[m5,{...m5,id:'duplicate'}]}}).success,true);
});

test('actual live response accepts six M5 plus six H1 and reports the exact violated invariant', () => {
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const exports={};runInNewContext(outputText,{exports,Set,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const response=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesM5H1Response.json',import.meta.url),'utf8'));
  const parse=v=>exports.viewSchema.safeParse(v);
  assert.equal(response.structure.levels.length,12);
  assert.equal(response.structure.maxLevels,6);
  assert.equal(parse(response).success,true);
  const overflow=parse({...response,structure:{...response.structure,maxLevels:5}});
  assert.equal(overflow.success,false);
  assert.equal(overflow.error.issues.length,1);
  assert.match(overflow.error.issues[0].message,/H1: 6 > 5/);
  const changed=structuredClone(response);
  changed.structure.levels[0].confirmedAtMsc=response.candles.at(-1).availableAtMsc+1;
  const future=parse(changed);
  assert.equal(future.success,false);
  assert.deepEqual(Array.from(future.error.issues[0].path),['structure','levels',0,'confirmedAtMsc']);
  assert.match(future.error.issues[0].message,/apos o instante disponivel/);
  changed.structure.levels[0]={...response.structure.levels[1],id:'duplicate'};
  const duplicate=parse(changed);
  assert.equal(duplicate.success,true);

});


test('opening H1 preparation bypasses M5 quota and rejects confirmations after opening even at a later cursor', () => {
  const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  const exports={};runInNewContext(outputText,{exports,Set,Date,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const response=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesPreparedH1.json',import.meta.url),'utf8'));
  const h1=response.structure.levels.filter(l=>l.timeframeMinutes===60);
  response.structure.levels=[...response.structure.levels.filter(l=>l.timeframeMinutes===5).slice(0,1),...h1];
  response.structure.maxLevels=1;
  assert.equal(h1.length,8);assert.equal(exports.viewSchema.safeParse(response).success,true);
  const late=structuredClone(response);
  late.structure.levels.find(l=>l.timeframeMinutes===60).confirmedAtMsc=response.structure.preparation.sessionOpenMsc+1;
  assert.equal(exports.viewSchema.safeParse(late).success,false);
  const candidateFuture=structuredClone(response);
  candidateFuture.structure.preparation.candidates[0].confirmedAtMsc=response.structure.preparation.sessionOpenMsc+1;
  assert.equal(exports.viewSchema.safeParse(candidateFuture).success,false);
  const clockMissing=structuredClone(response); delete clockMissing.availableAtMsc;
  assert.equal(exports.viewSchema.safeParse(clockMissing).success,false);
  const c=controller(),received=[];
  c.attachChart({setHistory(){},updateLast(){},setStructureLevels:levels=>received.push(levels)});
  c.apply(response);assert.equal(received.at(-1).length,8);
  assert.equal(received.at(-1).every(l=>l.timeframeMinutes===60),true);
  c.setVisibility(true,true);assert.equal(received.at(-1).length,9);
  c.setVisibility(false,true);assert.equal(received.at(-1).length,8);
});
