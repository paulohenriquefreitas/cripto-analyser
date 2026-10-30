import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { FibonacciMovementEngine, analyzeFibonacci, fibVersion, observeFibonacci } from '../src/features/threecandles/models/FibonacciMovementEngine.ts';
import { FibonacciPrimitive } from '../src/features/threecandles/components/FibonacciPrimitive.ts';

const anchor=(i,type,price,changes={})=>({id:`WIN$N:5:${i}:${type}`,symbol:'WIN$N',timeframeMinutes:5,type,price,
  extremeTimeMsc:i*300000,confirmedAtMsc:(i+3)*300000,...changes});
const bar=(i,high,low,close,closed=true)=>({candle:{bucketStartTimeMsc:i*300000,high,low,close},closed,availableAtMsc:(i+1)*300000});

test('UP/DOWN extensions and structural reversals preserve history and manual anchors',()=>{
  for(const direction of ['UP','DOWN']) {
    const first=anchor(1,direction==='UP'?'BOTTOM':'TOP',direction==='UP'?100:200);
    const second=anchor(3,direction==='UP'?'TOP':'BOTTOM',direction==='UP'?200:100);
    const third=anchor(5,second.type,direction==='UP'?240:60);
    const contrary=anchor(7,first.type,direction==='UP'?80:220);
    const e=new FibonacciMovementEngine(5);
    e.advance([first,second,third],second.confirmedAtMsc-1);assert.equal(e.versions.length,0);
    e.advance([first,second,third],second.confirmedAtMsc);assert.equal(e.versions.length,1);
    const initial=JSON.stringify(e.versions);
    const manual=fibVersion(direction==='UP'?second:first,direction==='UP'?first:second,second.confirmedAtMsc,'MANUAL','USER_SELECTED_ANCHORS',undefined,1);
    e.advance([contrary,third,second,first],Infinity);
    assert.equal(e.versions.length,3);assert.equal(e.versions[1].direction,direction);
    assert.notEqual(e.versions[2].direction,direction);
    assert.equal(e.versions[2].reason, direction==='UP'?'CONFIRMED_STRUCTURAL_REVERSAL_DOWN':'CONFIRMED_STRUCTURAL_REVERSAL_UP');
    assert.equal(e.versions[1].fib50,direction==='UP'?170:130);
    assert.deepEqual(JSON.parse(initial),e.versions.slice(0,1));
    assert.equal(manual.fib50,150);assert.equal(e.ambiguities.length,0);assert.equal(e.events.filter(x=>x.kind==='DIRECTION_CHANGED').length,1);
    e.advance([first,second,third,contrary],Infinity);assert.equal(e.versions.length,3);
  }
});

test('manual requires confirmed, same-symbol/timeframe, ordered-price anchors from different origin candles',()=>{
  const high=anchor(2,'TOP',201),low=anchor(1,'BOTTOM',100);
  assert.equal(fibVersion(high,low,high.confirmedAtMsc,'MANUAL','test',undefined,1).fib50,150.5);
  for(const [h,l,t] of [[high,low,0],[high,{...low,timeframeMinutes:60},Infinity],[high,{...low,symbol:'OTHER'},Infinity],
    [high,{...low,extremeTimeMsc:high.extremeTimeMsc},Infinity],[high,{...low,price:202},Infinity]])
    assert.throws(()=>fibVersion(h,l,t,'MANUAL','test',undefined,1));
  const same=new FibonacciMovementEngine(5);
  same.advance([high,{...low,extremeTimeMsc:high.extremeTimeMsc}],Infinity);
  assert.equal(same.versions.length,0);assert.ok(same.ambiguities.length>0);
});

test('50% interactions observe only closed bars and a version known at bar open; they never redraw anchors',()=>{
  const v=fibVersion(anchor(2,'TOP',200),anchor(1,'BOTTOM',100),1500000,'MANUAL','test',undefined,1);
  const before=JSON.stringify(v);
  const observations=observeFibonacci([v],[bar(3,200,100,140),bar(5,148,140,145),bar(6,160,140,145),
    bar(7,170,140,160),bar(8,170,140,145,false)],5);
  assert.ok(observations.some(o=>o.kind==='APPROACH_50'));
  assert.ok(observations.some(o=>o.kind==='TEST_50'));
  assert.ok(observations.some(o=>o.kind==='POSSIBLE_BREAK_50'));
  assert.ok(observations.some(o=>o.kind==='POSSIBLE_REJECTION_50'));
  assert.ok(observations.every(o=>o.atMsc>=1800000&&o.atMsc!==2700000));
  assert.equal(JSON.stringify(v),before);
});

test('real October16 prefixes rebuild deterministically, isolate M5/H1 and ignore all future pivots/candles',()=>{
  const view=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/fibonacci/replay.json',import.meta.url),'utf8'));
  const final=analyzeFibonacci(view),before=JSON.stringify(view);
  const today=view.candles.filter(c=>new Date(c.candle.bucketStartTimeMsc).toISOString().startsWith('2026-10-16'));
  const streams=[new FibonacciMovementEngine(5),new FibonacciMovementEngine(60)];
  for(const c of today) {
    const cutoff=c.availableAtMsc;
    // Deliberately supply the future-containing full snapshot; the adapter must gate both arrays.
    const fromFull=analyzeFibonacci({...view,availableAtMsc:cutoff});
    const prefix={...view,availableAtMsc:cutoff,candles:view.candles.filter(b=>b.availableAtMsc<=cutoff),
      structure:{...view.structure,confirmedPivots:view.structure.confirmedPivots.filter(p=>p.confirmedAtMsc<=cutoff)}};
    const rebuilt=analyzeFibonacci(prefix);
    assert.deepEqual(fromFull,rebuilt);
    for(let i=0;i<2;i++) {
      streams[i].advance(rebuilt.anchors,cutoff);
      assert.deepEqual(streams[i].versions,rebuilt.lanes[i].versions);
      assert.deepEqual(rebuilt.lanes[i].versions,final.lanes[i].versions.filter(v=>v.decidedAtMsc<=cutoff));
      assert.ok(rebuilt.lanes[i].versions.every(v=>v.high.timeframeMinutes===v.low.timeframeMinutes));
      assert.equal(new Set(rebuilt.lanes[i].versions.map(v=>v.id)).size,rebuilt.lanes[i].versions.length);
    }
  }
  const micro=final.lanes[0].versions;
  assert.equal(micro.length,9);assert.equal(micro[0].fib50,209025);assert.equal(micro[1].fib50,209055);
  assert.equal(micro[1].low.id,micro[0].low.id);assert.equal(micro[1].direction,'UP');
  assert.equal(micro[1].decidedAtMsc,Date.parse('2026-10-16T10:00:00Z'));
  assert.equal(JSON.stringify(view),before);
});

test('primitive draws 2.5px continuous midpoint, discreet extrema, contrasting label and no autoscale contribution',()=>{
  const v=fibVersion(anchor(2,'TOP',200),anchor(1,'BOTTOM',100),1500000,'MANUAL','test',undefined,1);
  const fib=new FibonacciPrimitive('#FFFFFF','#000000'),draws=[];
  const context={save(){},restore(){},setLineDash(d){this.dash=d;},beginPath(){},moveTo(x,y){this.y=y;},lineTo(){},stroke(){draws.push({width:this.lineWidth,color:this.strokeStyle,dash:this.dash,y:this.y});},arc(){},fill(){},fillRect(){},fillText(){},strokeText(){},measureText:t=>({width:t.length*7})};
  fib.attached({chart:{timeScale:()=>({timeToCoordinate:t=>t/10,options:()=>({barSpacing:8})}),paneSize:()=>({height:300})},
    series:{priceToCoordinate:p=>300-p},requestUpdate(){}});
  fib.set({primary:v,anchors:[v.high,v.low],pick:'TOP'},[0,300,600,900,1200]);
  fib.paneViews()[0].renderer().draw({useMediaCoordinateSpace:fn=>fn({context,mediaSize:{width:800,height:300}})});
  assert.ok(draws.some(d=>d.width===2.5&&d.color==='#FFFFFF'&&d.dash.length===0));
  assert.equal(fib.priceAxisViews()[0].text(),'FIBO 50% — 150');
  assert.equal(fib.autoscaleInfo,undefined);
  assert.equal(fib.pick(60,100).id,v.high.id);
  const light=new FibonacciPrimitive('#111827','#FFFFFF');light.set({primary:v,anchors:[]},[]);
  assert.equal(light.priceAxisViews()[0].textColor(),'#111827');
  light.set({comparison:v,anchors:[]},[]);
  assert.equal(light.priceAxisViews()[0].text(),'COMPARAR 50% — 150');
});
