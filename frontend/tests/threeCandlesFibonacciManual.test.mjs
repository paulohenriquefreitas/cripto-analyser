import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import * as engine from '../src/features/threecandles/models/FibonacciMovementEngine.ts';

const fixture=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/fibonacci/replay.json',import.meta.url),'utf8'));
const at=time=>({...fixture,availableAtMsc:Date.parse(`2026-10-16T${time}:00Z`),
  candles:fixture.candles.filter(c=>c.availableAtMsc<=Date.parse(`2026-10-16T${time}:00Z`))});
const flush=async()=>{for(let i=0;i<6;i++)await Promise.resolve();};
function host(view,extra={}) {
  const states=[],memos=[];let index=0,mi=0,tree,scheduled=false,props={controller:{},view,candleDisplayTimeZone:'UTC',...extra};
  const render=()=>{index=mi=0;tree=component(props);};
  const react={
    useState(initial){const i=index++;if(!(i in states))states[i]=typeof initial==='function'?initial():initial;
      return [states[i],v=>{states[i]=typeof v==='function'?v(states[i]):v;if(!scheduled){scheduled=true;queueMicrotask(()=>{scheduled=false;render();});}}];},
    useMemo(fn,deps){const i=mi++;if(!memos[i]||deps.some((d,j)=>d!==memos[i].deps[j]))memos[i]={deps,value:fn()};return memos[i].value;},
  };
  const exports={};
  const {outputText}=ts.transpileModule(readFileSync(new URL('../src/features/threecandles/components/ThreeCandlesWorkspace.tsx',import.meta.url),'utf8'),
    {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}});
  runInNewContext(outputText,{exports,Intl,Date,Error,require:name=>{
    if(name==='react')return react;
    if(name==='@mui/material')return new Proxy({},{get:(_,k)=>k});
    if(name==='react/jsx-runtime')return{jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})};
    if(name.includes('FibonacciMovementEngine'))return engine;
    if(name.includes('ThreeCandlesChart'))return{ThreeCandlesChart:'Chart'};
    if(name.includes('DowDebugPanel'))return{DowDebugPanel:'DowDebugPanel'};
    throw new Error(name);
  }});
  const component=exports.ThreeCandlesWorkspace;render();
  const nodes=()=>{const list=[];function visit(n){if(!n||typeof n!=='object')return;if(Array.isArray(n)){n.forEach(visit);return;}list.push(n);visit(n.props?.children);}visit(tree);return list;};
  return {nodes,field:label=>nodes().find(n=>n.props?.label===label),button:label=>nodes().find(n=>n.type==='Button'&&n.props.children===label),
    chart:()=>nodes().find(n=>n.type==='Chart'),setView(v){props={...props,view:v};render();}};
}

test('chart picks and selectors create an independent manual version; auto advance and reset cannot overwrite it',async()=>{
  const h=host(at('10:25'));
  h.field('Fibonacci').props.control.props.onChange(null,true);await flush();
  const anchors=engine.analyzeFibonacci(at('10:25')).anchors.filter(a=>a.timeframeMinutes===5);
  const high=anchors.find(a=>a.type==='TOP'),low=anchors.find(a=>a.type==='BOTTOM');
  h.button('Selecionar máxima no gráfico').props.onClick();await flush();assert.equal(h.chart().props.overlay.pick,'TOP');
  h.chart().props.onPick(high);await flush();assert.equal(h.field('Máxima manual confirmada').props.value,high.id);
  h.button('Selecionar mínima no gráfico').props.onClick();await flush();h.chart().props.onPick(low);await flush();
  h.button('Aplicar Fibonacci manual').props.onClick();await flush();
  assert.equal(h.field('Modo Fibonacci').props.value,'MANUAL');
  assert.equal(h.chart().props.overlay.primary.fib50,209025);
  h.field('Comparar manual e automático').props.control.props.onChange(null,true);await flush();
  assert.equal(h.chart().props.overlay.comparison.fib50,209165);
  const manual=JSON.stringify(h.chart().props.overlay.primary);
  h.setView(at('12:00'));assert.equal(JSON.stringify(h.chart().props.overlay.primary),manual);
  assert.equal(h.chart().props.overlay.comparison.fib50,209437.5);
  h.setView(at('09:45'));assert.equal(h.chart().props.overlay.primary,undefined,'manual decision is not backdated after reset');
  h.setView(at('10:25'));assert.equal(JSON.stringify(h.chart().props.overlay.primary),manual);
  h.field('Fibonacci').props.control.props.onChange(null,false);await flush();assert.equal(h.chart().props.overlay.primary,undefined);
  h.field('Fibonacci').props.control.props.onChange(null,true);await flush();assert.equal(JSON.stringify(h.chart().props.overlay.primary),manual);
  h.field('Amplitude Fibonacci').props.onChange({target:{value:'60'}});await flush();
  assert.equal(h.chart().props.overlay.primary,undefined);assert.equal(h.field('Máxima manual confirmada').props.value,'');
  h.field('Amplitude Fibonacci').props.onChange({target:{value:'5'}});await flush();assert.equal(JSON.stringify(h.chart().props.overlay.primary),manual);
});

test('approved visibility defaults leave the chart outside every collapsed section',()=>{
  const h=host(at('10:25')),chart=h.chart().props;
  assert.equal(chart.dowOverlay.visible,true);assert.equal(chart.dowOverlay.labels,false);
  assert.equal(chart.dowFiftyOverlay.visible,true);assert.equal(chart.microDowOverlay.visible,true);
  assert.equal(chart.microDowOverlay.labels,false);assert.equal(chart.microDowOverlay.allFractals,false);
  assert.equal(chart.overlay.primary,undefined);assert.equal(chart.overlay.comparison,undefined);
  const panels=h.nodes().filter(n=>n.type==='details');
  assert.ok(panels.length>=10);assert.ok(panels.every(n=>n.props.open!==true));
  const descendants=n=>{const all=[];function visit(v){if(!v||typeof v!=='object')return;if(Array.isArray(v)){v.forEach(visit);return;}all.push(v);visit(v.props?.children);}visit(n);return all;};
  assert.ok(panels.every(p=>!descendants(p).some(n=>n.type==='Chart')));
  assert.ok(h.nodes().find(n=>n.props?.['data-testid']==='advanced-settings'));
  assert.ok(h.nodes().find(n=>n.props?.['data-testid']==='audit-diagnostics'));
});

test('all overlay choices survive snapshots and workspace remounts without touching analytical data',async()=>{
  const view=at('10:25'),before=JSON.stringify(view),presentation={current:{fibonacci:false,dow:true,dowFifty:true,dowLabels:false,micro:false,microLabels:false,allMicroFractals:false}};
  let h=host(view,{presentation});
  for(const label of ['Fibonacci','Mostrar todos os fractais micro confirmados','Mostrar etiquetas HH/HL/LH/LL micro']) {
    h.field(label).props.control.props.onChange(null,true);await flush();
  }
  h.field('Dow micro').props.control.props.onChange(null,true);await flush();
  const panel=h.nodes().find(n=>n.type==='DowDebugPanel');
  panel.props.onVisible(false);panel.props.onLabels(true);await flush();
  h.field('50% Dow principal').props.control.props.onChange(null,false);await flush();
  h.setView({...view,status:'PLAYING'});h.setView({...view,status:'PAUSED'});
  h=host(view,{presentation});const chart=h.chart().props;
  assert.equal(chart.dowOverlay.visible,false);assert.equal(chart.dowOverlay.labels,true);
  assert.equal(chart.dowFiftyOverlay.visible,false);assert.equal(chart.microDowOverlay.visible,true);
  assert.equal(chart.microDowOverlay.labels,true);assert.equal(chart.microDowOverlay.allFractals,true);
  assert.ok(chart.overlay.primary);assert.equal(JSON.stringify(view),before);
});

test('M15 hides unsupported Dow overlays without erasing the saved M5 choices',()=>{
  const view=at('10:25'),h=host({...view,timeframeMinutes:15},{structuralLevels:false});
  assert.equal(h.chart().props.dowOverlay.visible,false);assert.equal(h.chart().props.dowFiftyOverlay.visible,false);
  assert.equal(h.field('50% Dow principal').props.control.props.disabled,true);
  assert.ok(h.nodes().find(n=>n.type==='DowDebugPanel').props.disabled);
  const fifty=h.nodes().find(n=>n.props?.['data-testid']==='dow-fifty-current');
  assert.ok(fifty.props.children.includes('indisponível em M15'));
});

test('Dow visibility and labels are independent of manual Fibonacci and consume backend data unchanged',async()=>{
  const view=at('10:25'),dow={state:'UP',pivots:[],movements:[],events:[]};
  const h=host({...view,dow});
  const panel=()=>h.nodes().find(n=>n.type==='DowDebugPanel');
  const automatic=JSON.stringify(h.chart().props.overlay);
  assert.equal(h.chart().props.dowOverlay.snapshot,dow);
  panel().props.onVisible(true);await flush();assert.equal(h.chart().props.dowOverlay.visible,true);
  panel().props.onLabels(false);await flush();assert.equal(h.chart().props.dowOverlay.labels,false);
  panel().props.onVisible(false);await flush();assert.equal(h.chart().props.dowOverlay.snapshot,dow);
  assert.equal(JSON.stringify(h.chart().props.overlay),automatic);
});


test('Dow midpoint controls and historical audit cannot change manual or legacy Fibonacci or reactivate an old wave',async()=>{
  const views=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/dow-fifty/WIN_N-prefixes.json',import.meta.url),'utf8'));
  const view=views.at(-1),h=host(view),fib=structuredClone(h.chart().props.overlay);
  h.field('50% Dow principal').props.control.props.onChange({},true);await flush();
  assert.equal(h.chart().props.dowFiftyOverlay.visible,true);assert.deepEqual(structuredClone(h.chart().props.overlay),fib);
  const current=view.dowFifty.currentWaveId,old=view.dowFifty.references[0].wave.id;
  h.chart().props.onPickDowFifty(current);await flush();
  h.field('Auditar onda 50% Dow').props.onChange({target:{value:old}});await flush();
  assert.equal(h.chart().props.dowFiftyOverlay.snapshot.currentWaveId,current);
  assert.deepEqual(structuredClone(h.chart().props.overlay),fib);
  h.field('50% Dow principal').props.control.props.onChange({},false);await flush();
  assert.equal(h.chart().props.dowFiftyOverlay.visible,false);
});

test('four independent checkboxes default on and retain calculation snapshots for every visibility combination',async()=>{
  const view=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/micro-fifty/30-1025-after.json',import.meta.url),'utf8'));
  const original=JSON.stringify(view),presentation={current:{fibonacci:false,dow:true,micro:true,dowFifty:true,microFifty:true,opening:true}};
  let h=host(view,{presentation});
  assert.equal(h.field('Fractais'),undefined);
  for(const label of ['Dow principal','Dow micro','50% Dow principal','50% Dow micro','Abertura experimental'])assert.equal(h.field(label).props.control.props.checked,true);
  for(let mask=0;mask<16;mask++) {
    const labels=['Dow principal','Dow micro','50% Dow principal','50% Dow micro'];
    for(let i=0;i<4;i++){h.field(labels[i]).props.control.props.onChange(null,Boolean(mask&(1<<i)));await flush();}
    const chart=h.chart().props;
    for(const [i,key] of ['dowOverlay','microDowOverlay','dowFiftyOverlay','microFiftyOverlay'].entries())assert.equal(chart[key].visible,Boolean(mask&(1<<i)));
    assert.equal(chart.microFiftyOverlay.snapshot,view.microDowFifty);assert.equal(chart.dowFiftyOverlay.snapshot,view.dowFifty);
    assert.equal(h.field('Abertura experimental').props.control.props.checked,true);
    assert.equal(JSON.stringify(view),original);
  }
  h.field('Dow micro').props.control.props.onChange(null,false);await flush();
  h.field('Abertura experimental').props.control.props.onChange(null,false);await flush();
  h=host(view,{presentation});assert.equal(h.field('Dow micro').props.control.props.checked,false);
  assert.equal(h.field('50% Dow micro').props.control.props.checked,true);assert.equal(h.field('Abertura experimental').props.control.props.checked,false);
  assert.equal(h.chart().props.openingOverlay.snapshot,view.openingStructure);assert.equal(h.chart().props.openingOverlay.visible,false);
  h.setView({...view,timeframeMinutes:15});
  for(const label of ['Dow principal','Dow micro','50% Dow principal','50% Dow micro'])assert.equal(h.field(label).props.control.props.disabled,true);
  assert.equal(h.chart().props.microFiftyOverlay.visible,false);
  h.setView(view);assert.equal(h.chart().props.microFiftyOverlay.visible,true);
});
