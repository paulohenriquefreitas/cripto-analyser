import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
import {MicroDowFiftyPrimitive} from '../src/features/threecandles/components/MicroDowFiftyPrimitive.ts';
import {DowFiftyPrimitive} from '../src/features/threecandles/components/DowFiftyPrimitive.ts';
import {analyzeFibonacci} from '../src/features/threecandles/models/FibonacciMovementEngine.ts';
const root=new URL('../src/features/threecandles/validation/micro-fifty/',import.meta.url);
const load=name=>JSON.parse(readFileSync(new URL(name,root),'utf8'));
const exports={};runInNewContext(ts.transpileModule(readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,{exports,Set,Date,require:n=>n==='zod'?{z}:{httpClient:{}}});

test('native micro fifty is causal, independent, and cannot fabricate waves, anchors, substitutions or current references',()=>{
  for(const name of ['29-1600','29-1610','30-1015','30-1025']) {
    const after=load(`${name}-after.json`),before=load(`${name}-before.json`);
    const parsed=exports.viewSchema.safeParse(after);assert.equal(parsed.success,true,JSON.stringify(parsed.error));
    const {microDowFifty,...legacy}=after;assert.deepEqual(legacy,before);
    assert.deepEqual(analyzeFibonacci(after),analyzeFibonacci(before));
    assert.equal(microDowFifty.currentWaveId,after.microDow.movements.at(-1).id);
  }
  const v=load('30-1025-after.json');assert.equal(v.microDowFifty.references.at(-1).wave.level50,169472.5);
  assert.equal(v.dowFifty.references.at(-1).wave.level50,169427.5);
  for(const change of [f=>f.symbol='WINV26',f=>f.availableAtMsc++,f=>f.currentWaveId=f.references[0].wave.id,
    f=>f.references.at(-1).wave.level50++,f=>f.references.at(-1).wave.availableAtMsc++,
    f=>f.references.at(-1).wave.initial.confirmedAtMsc=v.availableAtMsc+1,
    f=>f.references.at(-1).wave.fromId='opening',f=>f.references[0].supersededAtMsc=null,
    f=>f.events.at(-1).atMsc++,f=>f.references.at(-1).wave.initial.price=v.openingStructure.initialLeg.origin.price]) {
    const bad=structuredClone(v);change(bad.microDowFifty);assert.equal(exports.viewSchema.safeParse(bad).success,false);
  }
});

function canvas() {
  const lines=[],texts=[];let from,zoom=1;
  const ctx={save(){},restore(){},setLineDash(v){this.dash=v;},beginPath(){},moveTo(x,y){from=[x,y];},
    lineTo(x,y){lines.push({from,to:[x,y],width:this.lineWidth,color:this.strokeStyle,dash:this.dash});},stroke(){},
    fillText(text,x,y){texts.push({text,x,y,color:this.fillStyle});},measureText(t){return{width:t.length*5};}};
  const attachment={chart:{timeScale:()=>({timeToCoordinate:t=>t*zoom,logicalToCoordinate:t=>t*zoom})},
    series:{priceToCoordinate:p=>300-(p-169000)/10},requestUpdate(){}};
  const target={useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:20000000000,height:480}})};
  return{lines,texts,attachment,target,zoom:v=>zoom=v};
}
test('micro 50 extends horizontally in micro cyan, dotted and thinner, with independent visibility and no autoscale',()=>{
  const v=load('30-1025-after.json'),before=JSON.stringify(v),c=canvas(),p=new MicroDowFiftyPrimitive();p.attached(c.attachment);
  const draw=(visible=true,s=v.microDowFifty)=>{p.set({snapshot:s,visible});p.paneViews()[0].renderer().draw(c.target);};
  draw();assert.equal(c.lines.length,1);const line=c.lines[0];assert.equal(line.from[1],line.to[1]);
  assert.equal(line.color,'#06B6D4');assert.equal(line.width,1);assert.deepEqual(line.dash,[2,4]);assert.ok(line.to[0]>line.from[0]);
  assert.ok(c.texts[0].text.startsWith('DOW MICRO 50% — '));assert.equal(p.autoscaleInfo,undefined);
  c.zoom(2);draw();assert.equal(c.lines[1].from[0],line.from[0]*2);
  draw(false);assert.equal(c.lines.length,2);
  const future=structuredClone(v.microDowFifty);future.references.at(-1).wave.availableAtMsc++;draw(true,future);assert.equal(c.lines.length,2);
  const offscreen=structuredClone(v.microDowFifty);offscreen.references.at(-1).wave.level50=200000;
  const labels=c.texts.length;draw(true,offscreen);assert.equal(c.lines.length,2);assert.equal(c.texts.length,labels);
  assert.equal(JSON.stringify(v),before);
});

test('coincident and nearby micro/principal levels keep true line prices and separated label baselines',()=>{
  const v=load('30-1015-after.json');
  for(const delta of [0,5,-5,100]) {
    const c=canvas(),main=new DowFiftyPrimitive(),micro=new MicroDowFiftyPrimitive();main.attached(c.attachment);micro.attached(c.attachment);
    const principal=v.dowFifty.references.at(-1).wave.level50;
    const s=structuredClone(v.microDowFifty);s.references.at(-1).wave.level50=principal+delta;
    main.set({snapshot:v.dowFifty,visible:true});main.paneViews()[0].renderer().draw(c.target);
    micro.set({snapshot:s,visible:true,principalLevel:principal});micro.paneViews()[0].renderer().draw(c.target);
    assert.equal(c.lines[0].color,'#FFFFFF');assert.equal(c.lines[0].width,2.5);
    assert.equal(c.lines[1].from[1],c.attachment.series.priceToCoordinate(principal+delta));
    if(delta===0)assert.equal(c.lines[0].from[1],c.lines[1].from[1]);
    assert.ok(Math.abs(c.texts[0].y-c.texts[1].y)>=20);
  }
});
