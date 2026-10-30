import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
import {OpeningStructurePrimitive} from '../src/features/threecandles/components/OpeningStructurePrimitive.ts';
import {analyzeFibonacci} from '../src/features/threecandles/models/FibonacciMovementEngine.ts';

const root=new URL('../src/features/threecandles/validation/opening-structure/',import.meta.url);
const load=name=>JSON.parse(readFileSync(new URL(name,root),'utf8'));
const exports={};
runInNewContext(ts.transpileModule(readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,
  {exports,Set,Date,require:name=>name==='zod'?{z}:{httpClient:{}}});

test('native October30 transport preserves causal opening and rejects future references, histories and foreign symbols',()=>{
  const view=load('0935-after.json');
  assert.equal(exports.viewSchema.safeParse(view).success,true);
  for(const change of [s=>s.availableAtMsc++,s=>s.symbol='WINV26',s=>s.initialLeg.destination.availableAtMsc=view.availableAtMsc+1,
    s=>s.legHistory[0].availableAtMsc=view.availableAtMsc+1,s=>s.gap.points++,s=>s.windows[0].availableAtMsc=view.availableAtMsc+1]) {
    const changed=structuredClone(view);change(changed.openingStructure);
    assert.equal(exports.viewSchema.safeParse(changed).success,false);
  }
});

test('before/after have identical micro, main, fifty and legacy Fibonacci, independent of opening thresholds',()=>{
  for(const clock of ['0910','0925','0935','0955','1015','1030']) {
    const before=load(`${clock}-before.json`),after=load(`${clock}-after.json`);
    assert.deepEqual(after.dow,before.dow);assert.deepEqual(after.microDow,before.microDow);assert.deepEqual(after.dowFifty,before.dowFifty);
    assert.deepEqual(analyzeFibonacci(after),analyzeFibonacci(before));
    const {openingStructure,...legacy}=after;assert.deepEqual(legacy,before);
    assert.equal(openingStructure.previousSession.officialSettlement,null);
  }
});

test('opening renderer draws a separate GAP, filters only the intraday leg and never invents same-M5 order',()=>{
  const primitive=new OpeningStructurePrimitive(),lines=[],labels=[];let from,scale=1;
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){lines.push([from,[x,y]]);},stroke(){},
    fillText(text){labels.push(text);},measureText(text){return {width:text.length*5};}};
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*scale})},series:{priceToCoordinate:p=>p},requestUpdate(){}});
  const draw=(s,minimumPoints=0,visible=true)=>{
    primitive.set({snapshot:s,minimumPoints,visible});
    primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:1500,height:800}})});
  };
  const native=load('0910-after.json').openingStructure;
  const original=JSON.stringify(native);draw(native);
  assert.equal(lines.length,2);assert.ok(labels.some(t=>t.startsWith('GAP')));assert.ok(labels.some(t=>t.includes('EM FORMAÇÃO')));
  assert.equal(lines[1][0][0],lines[1][1][0],'GAP is vertical at open, never a traded diagonal from yesterday');
  assert.notEqual(lines[0][0][0],lines[0][1][0]);
  scale=2;draw(native);assert.equal(lines[2][1][0],native.initialLeg.destination.formationTimeMsc/1000*2);
  const small=structuredClone(native);small.initialLeg.amplitudePoints=200;
  draw(small,250);assert.ok(labels.at(-1).startsWith('GAP'));
  const count=lines.length;draw(native,0,false);assert.equal(lines.length,count);
  const same=structuredClone(native);same.initialLeg.intrabarOrder='UNKNOWN_SAME_M5';draw(same);assert.equal(lines.length,count+1);
  const future=structuredClone(native);future.initialLeg.availableAtMsc++;draw(future);assert.equal(lines.length,count+2);
  assert.equal(JSON.stringify(native),original);assert.equal(primitive.autoscaleInfo,undefined);
});
