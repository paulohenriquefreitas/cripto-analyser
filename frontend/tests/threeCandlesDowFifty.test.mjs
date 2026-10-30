import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
import {DowFiftyPrimitive} from '../src/features/threecandles/components/DowFiftyPrimitive.ts';
const prefixes=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/dow-fifty/WINZ26-prefixes.json',import.meta.url),'utf8'));
const first=prefixes.find(v=>v.dowFifty.currentWaveId),last=prefixes.at(-1);

test('single white midpoint is revealed at completion and anchored at wave center, uses native coordinates, picks only current reference and never autoscales',()=>{
  const p=new DowFiftyPrimitive(),lines=[],labels=[],widths=[];let from,scale=1;
  const wave=first.dowFifty.references[0].wave;
  const known=(wave.initial.extremeTimeMsc+wave.terminal.extremeTimeMsc)/2000;
  p.attached({chart:{timeScale:()=>({timeToCoordinate:t=>(t-known)*scale+100,logicalToCoordinate:i=>i*10})},series:{priceToCoordinate:y=>y*scale},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){lines.push({from,to:[x,y],color:this.strokeStyle,width:this.lineWidth});},stroke(){},fillText(t){labels.push(t);}};
  const draw=()=>p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})});
  p.set({snapshot:first.dowFifty,visible:true});draw();
  assert.equal(lines.length,1);assert.equal(lines[0].color,'#FFFFFF');assert.equal(lines[0].width,2.5);
  assert.deepEqual(lines[0].from,[100,first.dowFifty.references[0].wave.level50]);assert.equal(lines[0].to[0],798);
  assert.ok(labels[0].startsWith('DOW 50%'));assert.equal(p.autoscaleInfo,undefined);
  assert.equal(p.pick(300,lines[0].from[1]),first.dowFifty.currentWaveId);
  scale=2;draw();assert.equal(lines.at(-1).from[1],first.dowFifty.references[0].wave.level50*2);
  p.set({snapshot:{...first.dowFifty,availableAtMsc:wave.recognizedAtMsc-1},visible:true});draw();assert.equal(lines.length,2);
  p.set({snapshot:last.dowFifty,visible:false});draw();assert.equal(lines.length,2);assert.equal(p.pick(300,lines[0].from[1]),undefined);
});
test('wave center uses fractional native logical coordinates without snapping to a candle',()=>{
  const p=new DowFiftyPrimitive(),lines=[];let from,logical;
  const ref=first.dowFifty.references[0],time=ref.wave.recognizedAtMsc/1000;
  p.attached({chart:{timeScale:()=>({timeToCoordinate:()=>null,logicalToCoordinate:i=>{logical=i;return i*10;}})},series:{priceToCoordinate:y=>y},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){lines.push([from,[x,y]]);},stroke(){},fillText(){}};
  p.set({snapshot:first.dowFifty,visible:true},[time-600,time-300]);
  p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})});
  const center=(ref.wave.initial.extremeTimeMsc+ref.wave.terminal.extremeTimeMsc)/2000;
  const expected=(center-(time-600))/300;
  assert.equal(logical,(ref.wave.terminal.extremeTimeMsc/1000-(time-600))/300);assert.equal(lines[0][0][0],Math.max(0,expected*10));
});
test('transport requires a sealed principal wave and rejects future, fabricated, terminal-only or reactivated references',()=>{
  const exports={};const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  runInNewContext(outputText,{exports,Set,Map,Date,Math,require:n=>n==='zod'?{z}:{httpClient:{}}});
  assert.equal(exports.viewSchema.safeParse(first).success,true);assert.equal(exports.viewSchema.safeParse(last).success,true);
  for(const alter of [v=>v.dowFifty.references[0].wave.level50++,v=>v.dowFifty.references[0].wave.recognizedAtMsc=v.availableAtMsc+1,
    v=>v.dowFifty.references[0].wave.sourceEventId='invented',v=>v.dowFifty.references[0].wave.initial.id='WINZ26:5:micro:fake',
    v=>v.dowFifty.currentWaveId=v.dowFifty.references[0].wave.id,v=>v.dowFifty.events[0].atMsc=v.availableAtMsc+1,
    v=>delete v.dow,v=>v.dowFifty.observations[0].atMsc=v.availableAtMsc+1]) {
    const changed=structuredClone(last);alter(changed);assert.equal(exports.viewSchema.safeParse(changed).success,false);
  }
  assert.equal(exports.viewSchema.safeParse({...last,dowFifty:undefined}).success,true);
});


test('wave origin stays fixed when live recognition or subsequent bars change',()=>{
  const p=new DowFiftyPrimitive(),positions=[];const original=first.dowFifty.references[0].wave.recognizedAtMsc/1000;
  const s=structuredClone(first.dowFifty);s.references[0].wave.recognizedAtMsc=(original+60)*1000;s.availableAtMsc=(original+300)*1000;
  p.attached({chart:{timeScale:()=>({timeToCoordinate:()=>null,logicalToCoordinate:i=>{positions.push(i);return i*10;}})},series:{priceToCoordinate:y=>y},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(){},lineTo(){},stroke(){},fillText(){}};
  const draw=()=>p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})});
  p.set({snapshot:s,visible:true},[original-300,original]);draw();assert.equal(positions.at(-1),(s.references[0].wave.terminal.extremeTimeMsc/1000-(original-300))/300);
  p.set({snapshot:s,visible:true},[original-300,original,original+300]);draw();assert.equal(positions.at(-1),(s.references[0].wave.terminal.extremeTimeMsc/1000-(original-300))/300);
});

test('current confirmed movement is accepted before a third pivot exists',()=>{
  const exports={};const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  runInNewContext(outputText,{exports,Set,Map,Date,Math,require:n=>n==='zod'?{z}:{httpClient:{}}});
  const v=structuredClone(first),w=v.dowFifty.references[0].wave;
  const event=v.dow.events.find(e=>e.kind==='STRUCTURE_PIVOT_SELECTED'&&e.pivotId===w.terminal.id);
  w.recognizedAtMsc=w.terminal.confirmedAtMsc;w.sourceEventId=event.id;w.sealingPivotId=w.terminal.id;
  v.availableAtMsc=w.recognizedAtMsc;v.dow.availableAtMsc=w.recognizedAtMsc;
  v.dow.pivots=v.dow.pivots.filter(p=>p.extreme.confirmedAtMsc<=v.availableAtMsc);
  v.dow.movements=v.dow.movements.filter(m=>m.id===w.id);
  v.dow.events=v.dow.events.filter(e=>e.atMsc<=v.availableAtMsc);
  v.dowFifty={...v.dowFifty,criterion:'CONFIRMED_MOVEMENT_V2',availableAtMsc:v.availableAtMsc,events:[],observations:[],references:[v.dowFifty.references[0]]};
  assert.equal(v.dow.pivots.length,2);
  assert.equal(exports.viewSchema.safeParse(v).success,true,JSON.stringify(exports.viewSchema.safeParse(v).error));
});


const october29 = time => JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/dow-fifty-oct29/${time}.json`,import.meta.url),'utf8'));

test('October29 transport accepts the causal forming wave before pivot confirmation and rejects future or fabricated extremes',()=>{
  const exports={};const source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  runInNewContext(outputText,{exports,Set,Map,Date,Math,require:n=>n==='zod'?{z}:{httpClient:{}}});
  for(const time of ['1550','1555','1600','1605','1610']) {
    const parsed=exports.viewSchema.safeParse(october29(time));
    assert.equal(parsed.success,true,JSON.stringify(parsed.error));
  }
  for(const change of [v=>v.dowFifty.formingWave.recognizedAtMsc=v.availableAtMsc+1,
    v=>v.dowFifty.formingWave.level50++,v=>v.dowFifty.formingWave.high=172845,
    v=>v.dowFifty.formingWave.initial.id='invented',v=>v.dowFifty.formingWave.extremeTimeMsc=v.availableAtMsc+300000]) {
    const v=october29('1555');change(v);assert.equal(exports.viewSchema.safeParse(v).success,false);
  }
});

test('forming 50 replaces the previous visual line, follows observed extremes, and hands over seamlessly to confirmation',()=>{
  const p=new DowFiftyPrimitive(),strokes=[],labels=[];let from,to;
  const initial=october29('1555'),base=initial.candles[0].candle.bucketStartTimeMsc/1000;
  p.attached({chart:{timeScale:()=>({timeToCoordinate:t=>(t-base)/60,logicalToCoordinate:i=>i*5})},series:{priceToCoordinate:y=>y},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){to=[x,y];},
    stroke(){strokes.push({from,to,width:this.lineWidth});},fillText(t){labels.push(t);}};
  const draw=view=>{strokes.length=0;labels.length=0;p.set({snapshot:view.dowFifty,visible:true});
    p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:1000,height:600}})});};
  draw(initial);
  assert.equal(strokes.length,2);assert.equal(strokes.filter(s=>s.width===2.5).length,1);
  assert.equal(strokes.at(-1).from[1],171767.5);assert.ok(labels[0].includes('em forma'));
  assert.equal(p.pick(900,171767.5),undefined,'forming endpoint cannot select the old confirmed audit wave');
  draw(october29('1600'));assert.equal(strokes.at(-1).from[1],171915);
  draw(october29('1610'));assert.equal(strokes.length,1);assert.equal(strokes[0].from[1],171915);
  assert.ok(!labels[0].includes('em forma'));assert.equal(p.pick(900,171915),october29('1610').dowFifty.currentWaveId);
});
