import * as patternAudit from '../src/features/threecandles/models/threeCandlePatternAudit.ts';
import { MicroDowFiftyPrimitive } from '../src/features/threecandles/components/MicroDowFiftyPrimitive.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { FibonacciPrimitive } from '../src/features/threecandles/components/FibonacciPrimitive.ts';
import { DowFiftyPrimitive } from '../src/features/threecandles/components/DowFiftyPrimitive.ts';
import { DowStructurePrimitive } from '../src/features/threecandles/components/DowStructurePrimitive.ts';
import { OpeningStructurePrimitive } from '../src/features/threecandles/components/OpeningStructurePrimitive.ts';
import { SwingPivotPrimitive } from '../src/features/win/components/SwingPivotPrimitive.ts';
import * as groups from '../src/features/threecandles/models/m5DisplayGroups.ts';
import { analyzeFibonacci } from '../src/features/threecandles/models/FibonacciMovementEngine.ts';
const compile=(path,require)=>{const exports={};const {outputText}=ts.transpileModule(readFileSync(new URL(path,import.meta.url),'utf8'),
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}});
  runInNewContext(outputText,{exports,require,Intl,Date});return exports;};

const {ThreeCandlePatternPrimitive}=compile('../src/features/threecandles/components/ThreeCandlePatternPrimitive.ts',()=>patternAudit);

test('dedicated native chart preserves H1/M5, zoom and incremental candles when Fibonacci changes',()=>{
  const controller=compile('../src/features/threecandles/models/candleChartController.ts',()=>groups).createCandleChartController();
  const view=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/fibonacci/1100.json',import.meta.url),'utf8'));
  const before=JSON.stringify(view),ranges=[],priceOptions=[],primitives=[],effects=[],refs=[];
  const theme={palette:{mode:'dark',background:{paper:'#000'},text:{secondary:'#ddd'},success:{main:'#0f0'},error:{main:'#f00'},divider:'#333'}};
  let ri=0,ei=0,creations=0,settings,data=[];
  const chart={timeScale:()=>({applyOptions(){},scrollToPosition(){},setVisibleLogicalRange:r=>ranges.push(r),timeToCoordinate:t=>t,options:()=>({barSpacing:8})}),
    priceScale:()=>({applyOptions:o=>priceOptions.push(o)}),addSeries:()=>series,subscribeClick(){},paneSize:()=>({height:480}),remove(){}};
  const series={setData:d=>{data=d;},update:b=>{data=[...data.filter(d=>d.time!==b.time),b];},priceToCoordinate:p=>p,
    attachPrimitive:p=>{primitives.push(p);p.attached({chart,series,requestUpdate(){}});}};
  const {ThreeCandlesChart}=compile('../src/features/threecandles/components/ThreeCandlesChart.tsx',name=>{
    if(name==='react')return {useRef:initial=>refs[ri++]??(refs[ri-1]={current:initial===null?{}:initial}),useEffect:(fn,deps)=>{const i=ei++,old=effects[i];
      if(!old||deps.some((d,j)=>d!==old.deps[j])){old?.cleanup?.();effects[i]={deps,cleanup:fn()};}}};
    if(name==='@mui/material')return {Box:'Box',useTheme:()=>theme};
    if(name==='react/jsx-runtime')return {jsx:()=>null};
    if(name==='lightweight-charts')return {ColorType:{},createChart:(_,o)=>{creations++;settings=o;return chart;}};
    if(name.includes('ThreeCandlePatternPrimitive'))return {ThreeCandlePatternPrimitive};
    if(name.includes('SwingPivotPrimitive'))return {SwingPivotPrimitive};
    if(name.includes('FibonacciPrimitive'))return {FibonacciPrimitive};
    if(name.includes('MicroDowFiftyPrimitive'))return {MicroDowFiftyPrimitive};
    if(name.includes('DowFiftyPrimitive'))return {DowFiftyPrimitive};
    if(name.includes('OpeningStructurePrimitive'))return {OpeningStructurePrimitive};
    if(name.includes('DowStructurePrimitive'))return {DowStructurePrimitive};
    throw new Error(name);
  });
  const analysis=analyzeFibonacci(view),v=analysis.lanes[0].versions.at(-1);
  const render=(overlay,patternViewport)=>{ri=ei=0;ThreeCandlesChart({controller,zone:'UTC',overlay,patternViewport,onPick(){}});};
  render({primary:v,anchors:[]});controller.setVisibility(true,true);controller.apply(view);
  assert.equal(settings.rightPriceScale.autoScale,true);assert.equal(ranges.at(-1).to-ranges.at(-1).from,44);
  assert.equal(primitives[0].style.color,'#EAB308');assert.equal(primitives[1].style.color,'#FF00FF');
  assert.equal(primitives[1].levels.length,9);assert.equal(data.length,view.candles.length);
  const count=priceOptions.length;priceOptions.push({autoScale:false});ranges.push({from:2,to:10});
  render({primary:analysis.lanes[0].versions[0],anchors:[]});render({anchors:[]});render({primary:v,anchors:[]});
  controller.setM5Grouping(50);controller.setVisibility(false,true);controller.setVisibility(true,true);controller.apply(view);
  assert.equal(creations,1);assert.equal(priceOptions.length,count+1);assert.deepEqual(ranges.at(-1),{from:2,to:10});
  controller.fitChart();assert.equal(priceOptions.at(-1).autoScale,true);
  const scaleCount=priceOptions.length,focus={from:view.candles.at(-3).candle.bucketStartTimeMsc,to:view.candles.at(-1).candle.bucketStartTimeMsc,request:1};
  render({anchors:[]},focus);
  assert.equal(ranges.at(-1).from,view.candles.length-3-5);
  assert.equal(ranges.at(-1).to,view.candles.length-1+6);
  const focusedRange=JSON.stringify(ranges.at(-1));render({primary:v,anchors:[]},focus);
  assert.equal(JSON.stringify(ranges.at(-1)),focusedRange);assert.equal(priceOptions.length,scaleCount);
  assert.equal(JSON.stringify(view),before);effects.forEach(e=>e.cleanup?.());
});
