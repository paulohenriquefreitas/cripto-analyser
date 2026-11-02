import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import ts from 'typescript';
import {z} from 'zod';
import {DowStructurePrimitive} from '../src/features/threecandles/components/DowStructurePrimitive.ts';

const extreme=(id,type,price,origin,confirmation)=>({id:`WIN$N:5:${id}`,type,price,extremeTimeMsc:origin,confirmedAtMsc:confirmation,
  timeframeMinutes:5,relevance:'PROXIMITY',region:{high:price+1,low:price-1,midpoint:price,basis:'PIVOT_CANDLE'}});
const a=extreme('a','BOTTOM',100,1000,4000),b=extreme('b','TOP',200,2000,5000),future=extreme('c','BOTTOM',120,3000,7000);
const node=(extreme,classification,participation='STRUCTURE')=>({extreme,classification,participation,comparisonId:null,comparisonPrice:null,reason:'Java fixture'});
const snapshot={symbol:'WIN$N',timeframeMinutes:5,availableAtMsc:5000,state:'UNDEFINED',criterion:'EXPERIMENTAL_PAIRED_PIVOTS_CLOSE_BREAK_V1',
  pivots:[node(a,'FIRST'),node(b,'FIRST')],movements:[{id:'ab',fromId:a.id,toId:b.id,knownAtMsc:5000,kind:'UP'}],events:[]};

test('Dow draws backend edges at actual price/time coordinates, follows zoom, toggles labels and contributes no autoscale',()=>{
  const primitive=new DowStructurePrimitive(),segments=[],labels=[],dots=[];let scale=10,updates=0,from;
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from={x,y};},lineTo(x,y){segments.push({from,to:{x,y}});},stroke(){},fill(){},
    arc(x,y,r){dots.push({x,y,r});},fillText(t){labels.push(t);}};
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*scale})},series:{priceToCoordinate:p=>300-p},requestUpdate(){updates++;}});
  const draw=()=>primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width:800,height:480}})});
  primitive.set({snapshot,visible:true,labels:true});draw();
  assert.deepEqual(segments,[{from:{x:10,y:200},to:{x:20,y:100}}]);assert.equal(dots.length,2);assert.equal(labels.length,2);
  assert.equal(primitive.pick(20,100).extreme.id,b.id);assert.equal(primitive.autoscaleInfo,undefined);
  scale=30;draw();assert.equal(segments.at(-1).to.x,60);assert.equal(primitive.pick(60,100).extreme.id,b.id);
  primitive.set({snapshot,visible:true,labels:false});const count=labels.length;draw();assert.equal(labels.length,count);
  primitive.set({snapshot,visible:false,labels:true});const edges=segments.length;draw();assert.equal(segments.length,edges);assert.equal(primitive.pick(60,100),undefined);
  assert.equal(updates,3);
});

test('Dow renderer cannot reveal a future pivot or snap an unavailable origin to another candle',()=>{
  const primitive=new DowStructurePrimitive(),dots=[];
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t===1?null:t*10})},series:{priceToCoordinate:p=>p},requestUpdate(){}});
  primitive.set({snapshot:{...snapshot,pivots:[...snapshot.pivots,node(future,'HL')],movements:[...snapshot.movements,
    {id:'bc',fromId:b.id,toId:future.id,knownAtMsc:7000,kind:'DOWN'}]},visible:true,labels:true});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(){throw new Error('Missing or future edge drawn');},lineTo(){},stroke(){},fill(){},arc(x,y){dots.push([x,y]);},fillText(){}};
  primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});
  assert.deepEqual(dots,[[20,200]]);
});

test('break annotations use the event candle and reference price supplied by the backend',()=>{
  const primitive=new DowStructurePrimitive(),lines=[],texts=[];let from;
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*10})},series:{priceToCoordinate:p=>300-p},requestUpdate(){}});
  const s={...snapshot,availableAtMsc:9000,events:[{id:'break',atMsc:9000,kind:'RESISTANCE_BROKEN',pivotId:b.id,candleTimeMsc:6000}]};
  primitive.set({snapshot:s,visible:true,labels:false});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from={x,y};},lineTo(x,y){lines.push({from,to:{x,y}});},stroke(){},fill(){},arc(){},fillText(t){texts.push(t);}};
  primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});
  assert.deepEqual(lines.at(-1),{from:{x:20,y:100},to:{x:60,y:100}});assert.deepEqual(texts,['R↑']);
});

test('API consumes Dow server decisions and rejects foreign symbols, future pivots, events or movements',()=>{
  const exports={},source=readFileSync(new URL('../src/features/threecandles/api/threeCandlesApi.ts',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}});
  runInNewContext(outputText,{exports,Set,Date,require:name=>name==='zod'?{z}:{httpClient:{}}});
  const view={symbol:'WIN$N',timeframeMinutes:5,status:'PAUSED',availableAtMsc:5000,candles:[],dow:snapshot};
  assert.equal(exports.viewSchema.safeParse(view).success,true);
  for(const mutate of [d=>d.symbol='WINZ26',d=>d.availableAtMsc=7000,d=>d.pivots.push(node(future,'HL')),
    d=>d.movements[0].knownAtMsc=7000,d=>d.movements[0].toId='missing',d=>d.events.push({id:'f',atMsc:7000,kind:'SUPPORT_BROKEN',pivotId:null,affectedMovementId:null,stateBefore:'UP',stateAfter:'TRANSITION',reason:'future'})]) {
    const altered=structuredClone(view);mutate(altered.dow);assert.equal(exports.viewSchema.safeParse(altered).success,false);
  }
  const microView={...view,dow:undefined,microDow:{...snapshot,criterion:'EXPERIMENTAL_MICRO_STRICT_1_1_V1'}};
  assert.equal(exports.viewSchema.safeParse(microView).success,true);
  const futureMicro=structuredClone(microView);futureMicro.microDow.pivots.push(node(future,'HL'));
  assert.equal(exports.viewSchema.safeParse(futureMicro).success,false);
  assert.equal(exports.viewSchema.safeParse({...view,dow:undefined}).success,true,'old backend must remain usable without inventing frontend Dow');
});


test('micro uses independent dashed edges and explicit micro labels; visibility does not draw dominant Dow',()=>{
  const primitive=new DowStructurePrimitive('#06B6D4',true),dashes=[],texts=[];
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*10})},series:{priceToCoordinate:p=>p},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(d){dashes.push(d);},beginPath(){},moveTo(){},lineTo(){},stroke(){},fill(){},arc(){},fillText(t){texts.push(t);}};
  primitive.set({snapshot:{...snapshot,criterion:'EXPERIMENTAL_MICRO_STRICT_1_1_V1'},visible:true,labels:true});
  primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});
  assert.deepEqual(dashes[0],[5,4]);assert.ok(texts.every(t=>t.startsWith('micro ')));
});


test('opening reference is amber and separate, cannot appear before opposite confirmation, rejection draws no wave',()=>{
  const primitive=new DowStructurePrimitive('#06B6D4',true),lines=[],texts=[],colors=[];let from;
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*10})},series:{priceToCoordinate:p=>p},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){lines.push([from,[x,y]]);colors.push(this.strokeStyle);},stroke(){},fill(){},arc(){},fillText(t){texts.push(t);}};
  const anchor={id:'opening',type:'TOP',price:250,formationTimeMsc:1000,observedAtMsc:3000};
  const opening={status:'CONNECTED',anchors:[anchor],fromId:anchor.id,toId:b.id,knownAtMsc:5000,events:[],reason:'Boundary reference'};
  const draw=o=>{primitive.set({snapshot:{...snapshot,criterion:'EXPERIMENTAL_MICRO_STRICT_1_1_V1',opening:o},visible:true,labels:false});primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});};
  draw(opening);assert.equal(colors.at(-1),'#F59E0B');assert.deepEqual(lines.at(-1),[[10,250],[20,200]]);assert.ok(texts.includes('Abertura (referencia, nao pivo)'));
  const count=lines.length;draw({...opening,knownAtMsc:7000});assert.equal(lines.length,count+1);
  draw({...opening,status:'REJECTED'});assert.equal(lines.length,count+2);
  draw({...opening,status:'WAITING'});assert.equal(lines.length,count+3);
});


test('all-fractal markers do not redraw superseded segments or change the selected chain',()=>{
  const primitive=new DowStructurePrimitive('#06B6D4',true),dots=[],segments=[];let from;
  primitive.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t*10})},series:{priceToCoordinate:p=>p},requestUpdate(){}});
  const ctx={save(){},restore(){},setLineDash(){},beginPath(){},moveTo(x,y){from=[x,y];},lineTo(x,y){segments.push([from,[x,y]]);},stroke(){},fill(){},arc(x,y){dots.push([x,y]);},fillText(){}};
  const local=extreme('local','TOP',180,1500,4500);
  const s={...snapshot,criterion:'EXPERIMENTAL_MICRO_STRICT_1_1_V1',pivots:[node(a,'FIRST'),node(local,'FIRST','SUPERSEDED'),node(b,'HH')],selectedPivotIds:[a.id,b.id],movementHistory:[{id:'old',fromId:a.id,toId:local.id,knownAtMsc:4500,kind:'UP'},...snapshot.movements]};
  const draw=allFractals=>{primitive.set({snapshot:s,visible:true,labels:false,allFractals});primitive.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});};
  draw(false);assert.equal(dots.length,2);assert.equal(segments.length,1);
  draw(true);assert.equal(dots.length,5);assert.equal(segments.length,2);assert.deepEqual(segments[0],segments[1]);
});


test('principal labels C1 C2 C3 above the actual closed confirmation candles, only after D is known',()=>{
  const base=Date.UTC(2026,9,6,9),m5=300000;
  const candles=[110,120,150,130,125].map((high,i)=>({candle:{symbol:'WIN$N',bucketStartTimeMsc:base+i*m5,open:100,high,low:90,close:100},timeframeMinutes:5,closed:true,availableAtMsc:base+(i+1)*m5}));
  const top=node(extreme('test','TOP',150,base+2*m5,base+5*m5),'FIRST');
  const snap={...snapshot,pivots:[top],movements:[],availableAtMsc:base+5*m5};
  const texts=[];
  function render({micro=false,visible=true,cutoff=snap.availableAtMsc,bars=candles,missingTime=null}={}) {
    const p=new DowStructurePrimitive('#E5E7EB',micro);
    p.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t===missingTime?null:(t-base/1000)/300})},series:{priceToCoordinate:p=>300-p},requestUpdate(){}});
    p.set({snapshot:{...snap,availableAtMsc:cutoff},candles:bars,visible,labels:false});
    const ctx={save(){},restore(){},setLineDash(){},beginPath(){},stroke(){},fill(){},arc(){},fillText(t,x,y){texts.push({t,x,y});}};
    p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});
  }
  const original=JSON.stringify(candles);
  render({cutoff:base+5*m5-1});assert.equal(texts.length,0);
  render();assert.deepEqual(texts,[{t:'C1',x:2,y:126},{t:'C2',x:3,y:146},{t:'C3',x:4,y:151}]);
  texts.length=0;render({micro:true});render({visible:false});assert.equal(texts.length,0);
  render({bars:candles.slice(0,4)});assert.equal(texts.length,0);
  render({bars:candles.map((b,i)=>i===4?{...b,closed:false}:b)});assert.equal(texts.length,0);
  render({missingTime:(base+3*m5)/1000});assert.deepEqual(texts.map(t=>t.t),['C1','C3']);
  assert.equal(JSON.stringify(candles),original);
});


test('2+1 candidate uses directional colors at C2 close and accompanies forming D without inventing a confirmed pivot',()=>{
  const base=Date.UTC(2026,9,6,9),step=300000;
  const bars=[110,120,150,130,160].map((high,i)=>({candle:{symbol:'WIN$N',bucketStartTimeMsc:base+i*step,open:100,high,low:90,close:100},timeframeMinutes:5,closed:i<4,availableAtMsc:base+(i===4?4: i+1)*step+(i===4?60000:0)}));
  const early={id:'early',type:'TOP',price:150,leftA:bars[0],leftB:bars[1],origin:bars[2],rightC:bars[3],availableAtMsc:base+4*step,status:'CANDIDATE_2_1',rightD:null,resolvedAtMsc:null,reason:'Test'};
  function draw(cutoff,status='CANDIDATE_2_1',visible=true,type='TOP') {
    const p=new DowStructurePrimitive(),texts=[];
    p.attached({chart:{timeScale:()=>({timeToCoordinate:t=>(t-base/1000)/300})},series:{priceToCoordinate:p=>300-p},requestUpdate(){}});
    p.set({snapshot:{...snapshot,pivots:[],movements:[],availableAtMsc:cutoff},earlyPivots:[{...early,status,type}],candles:bars,visible,labels:false});
    const ctx={save(){},restore(){},setLineDash(){},beginPath(){},stroke(){},fill(){},arc(){},fillText(t,x,y){texts.push({t,x,y,color:this.fillStyle});}};
    p.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx})});return texts;
  }
  assert.deepEqual(draw(early.availableAtMsc-1),[]);
  const closeC=draw(early.availableAtMsc);assert.deepEqual(closeC.map(t=>t.t),['C1','C2']);assert.ok(closeC.every(t=>t.color==='#FF3030'));
  assert.deepEqual(draw(base+4*step+60000).map(t=>t.t),['C1','C2','C3']);
  assert.ok(draw(early.availableAtMsc,'CANDIDATE_2_1',true,'BOTTOM').every(t=>t.color==='#00E676'));
  assert.deepEqual(draw(base+5*step,'INVALIDATED'),[]);assert.deepEqual(draw(base+5*step,'CONFIRMED_2_2'),[]);
  assert.deepEqual(draw(early.availableAtMsc,'CANDIDATE_2_1',false),[]);
});
