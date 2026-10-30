import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FibonacciPrimitive } from '../src/features/threecandles/components/FibonacciPrimitive.ts';
import { fibVersion } from '../src/features/threecandles/models/FibonacciMovementEngine.ts';

const anchor=(i,type,price)=>({id:`WIN$N:5:${i}:${type}`,symbol:'WIN$N',timeframeMinutes:5,type,price,
  extremeTimeMsc:i*300000,confirmedAtMsc:(i+3)*300000});
const version=(up=false,mode='MANUAL')=>fibVersion(anchor(up?2:1,'TOP',200),anchor(up?1:2,'BOTTOM',100),1500000,mode,'test',undefined,1);
function harness(color='#FFFFFF') {
  const transforms={sx:0.1,tx:10,sy:1,ty:500,missing:false},records={strokes:[],fills:[],texts:[]};let path=[],width=800,height=600;
  const ctx={save(){},restore(){},setLineDash(v){this.dash=[...v];},beginPath(){path=[];},moveTo(x,y){path.push([x,y]);},lineTo(x,y){path.push([x,y]);},arc(){},fill(){},
    stroke(){records.strokes.push({path:[...path],width:this.lineWidth,color:this.strokeStyle,alpha:this.globalAlpha,dash:this.dash});},
    fillRect(x,y,w,h){records.fills.push({x,y,w,h,color:this.fillStyle,alpha:this.globalAlpha});},
    fillText(text,x,y){records.texts.push({text,x,y,color:this.fillStyle});},strokeText(){},measureText:t=>({width:t.length*7})};
  const fib=new FibonacciPrimitive(color,'#101010');
  const x=t=>transforms.missing&&t===300?null:t*transforms.sx+transforms.tx,y=p=>transforms.ty-p*transforms.sy;
  fib.attached({chart:{timeScale:()=>({timeToCoordinate:x,options:()=>({barSpacing:8})}),paneSize:()=>({width,height})},
    series:{priceToCoordinate:y},requestUpdate(){}});
  return {fib,transforms,records,x,y,resize(w,h){width=w;height=h;},draw(){records.strokes=[];records.fills=[];records.texts=[];
    for(const view of fib.paneViews())view.renderer().draw({useMediaCoordinateSpace:fn=>fn({context:ctx,mediaSize:{width,height}})});}};
}
const diagonal=(h,color='#FFFFFF')=>h.records.strokes.find(s=>s.color===color&&s.path.length===2&&s.path[0][0]!==s.path[1][0]&&s.path[0][1]!==s.path[1][1]);

test('two exact candle-price anchors produce Profit-reference levels, diagonal, blue amplitude band and inline labels',()=>{
  for(const up of [false,true]) {
    const h=harness(),v=version(up),first=up?v.low:v.high,second=up?v.high:v.low;
    h.fib.set({primary:v,anchors:[]},[300,600,900,1200]);h.draw();
    assert.deepEqual(diagonal(h).path,[[h.x(first.extremeTimeMsc/1000),h.y(first.price)],[h.x(second.extremeTimeMsc/1000),h.y(second.price)]]);
    assert.deepEqual(diagonal(h).dash,[2,4]);assert.equal(diagonal(h).color,'#FFFFFF');
    assert.deepEqual(h.records.fills,[{x:40,y:300,w:760,h:100,color:'#2563EB',alpha:0.1}]);
    assert.deepEqual(h.records.texts.filter(t=>t.text.includes('%')).map(t=>t.text),['0% — 200','50% — 150','100% — 100']);
    assert.ok(h.records.texts.some(t=>t.text==='A'));assert.ok(h.records.texts.some(t=>t.text==='B'));
    const mid=h.records.strokes.find(s=>s.width===2.5);
    assert.equal(mid.path[0][1],h.y(150));assert.deepEqual(mid.dash,[]);
    assert.equal(h.fib.autoscaleInfo,undefined);
  }
});

test('pan, both zoom axes and resize recompute geometry through native coordinate APIs without rewriting anchors',()=>{
  const h=harness(),v=version(),before=JSON.stringify(v);h.fib.set({primary:v,anchors:[]},[300,600,900,1200]);h.draw();
  const original=diagonal(h).path;
  h.transforms.sx=0.35;h.transforms.tx=-40;h.transforms.sy=1.3;h.transforms.ty=530;h.resize(1100,700);h.draw();
  assert.notDeepEqual(diagonal(h).path,original);
  assert.deepEqual(diagonal(h).path,[[h.x(300),h.y(200)],[h.x(600),h.y(100)]]);
  assert.equal(h.records.fills[0].x,65);assert.equal(h.records.fills[0].w,1035);
  assert.equal(h.records.fills[0].y,270);assert.equal(h.records.fills[0].h,130);
  assert.equal(JSON.stringify(v),before);
});

test('automatic anchor renewal redraws whole tool, while a compared manual tool retains its own endpoints',()=>{
  const h=harness(),manual=version(true),automatic=version(false,'EXPERIMENTAL');
  h.fib.set({primary:manual,comparison:automatic,anchors:[]},[300,600,900,1200]);h.draw();
  const manualPath=diagonal(h).path,before=JSON.stringify(manual);
  const expanded=fibVersion(automatic.high,anchor(3,'BOTTOM',80),1800000,'EXPERIMENTAL','extension',automatic,2);
  h.fib.set({primary:manual,comparison:expanded,anchors:[]},[300,600,900,1200,1500]);h.draw();
  assert.deepEqual(diagonal(h).path,manualPath);
  assert.ok(h.records.strokes.some(s=>s.color==='#38BDF8'&&s.path.some(([x,y])=>x===h.x(900)&&y===h.y(80))));
  assert.ok(h.records.texts.some(t=>t.text==='COMP 50% — 140'));
  assert.equal(JSON.stringify(manual),before);
});

test('missing candle coordinates are not snapped to a different candle; offscreen shading stays clipped',()=>{
  const h=harness(),v=version();h.fib.set({primary:v,anchors:[]},[300,600,900]);
  h.transforms.missing=true;h.draw();assert.equal(h.records.fills.length,0);assert.equal(h.records.strokes.length,0);
  h.transforms.missing=false;h.transforms.tx=-100;h.draw();assert.equal(h.records.fills[0].x,0);
  assert.equal(diagonal(h).path[0][0],-70,'real offscreen endpoint is retained');
  const light=harness('#111827');light.fib.set({primary:v,anchors:[]},[300,600,900]);light.draw();
  assert.equal(diagonal(light,'#111827').color,'#111827');assert.equal(light.records.texts[0].color,'#111827');
});
