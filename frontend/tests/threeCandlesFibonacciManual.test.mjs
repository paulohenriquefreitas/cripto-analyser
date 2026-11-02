import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import * as engine from '../src/features/threecandles/models/FibonacciMovementEngine.ts';
import * as patternPresentation from '../src/features/threecandles/models/patternPresentation.ts';

const fixture=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/fibonacci/replay.json',import.meta.url),'utf8'));
const at=time=>({...fixture,availableAtMsc:Date.parse(`2026-10-16T${time}:00Z`),
  candles:fixture.candles.filter(c=>c.availableAtMsc<=Date.parse(`2026-10-16T${time}:00Z`))});
const patternFixture=clock=>JSON.parse(readFileSync(new URL(`../src/features/threecandles/validation/pattern-detector-v2/${clock}.json`,import.meta.url),'utf8'));
const flush=async()=>{for(let i=0;i<6;i++)await Promise.resolve();};
function host(view,extra={}) {
  const states=[],memos=[];let index=0,mi=0,tree,scheduled=false,props={controller:{},view,candleDisplayTimeZone:'UTC',...extra};
  const render=()=>{index=mi=0;tree=component(props);};
  const react={
    useState(initial){const i=index++;if(!(i in states))states[i]=typeof initial==='function'?initial():initial;
      return [states[i],v=>{states[i]=typeof v==='function'?v(states[i]):v;if(!scheduled){scheduled=true;queueMicrotask(()=>{scheduled=false;render();});}}];},
    useMemo(fn,deps){const i=mi++;if(!memos[i]||deps.some((d,j)=>d!==memos[i].deps[j]))memos[i]={deps,value:fn()};return memos[i].value;},
  };
  const analyzedViews=[];
  const exports={};
  const {outputText}=ts.transpileModule(readFileSync(new URL('../src/features/threecandles/components/ThreeCandlesWorkspace.tsx',import.meta.url),'utf8'),
    {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}});
  runInNewContext(outputText,{exports,Intl,Date,Error,require:name=>{
    if(name==='react')return react;
    if(name==='@mui/material')return new Proxy({},{get:(_,k)=>k});
    if(name==='react/jsx-runtime')return{jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})};
    if(name.includes('FibonacciMovementEngine'))return {...engine,analyzeFibonacci:(...args)=>{analyzedViews.push(args[0]);return engine.analyzeFibonacci(...args);}};
    if(name.includes('patternPresentation'))return patternPresentation;
    if(name.includes('ExperimentalChopPanel'))return {ExperimentalChopPanel:'ChopPanel'};
    if(name.includes('ThreeCandlesChart'))return{ThreeCandlesChart:'Chart'};
    if(name.includes('DowDebugPanel'))return{DowDebugPanel:'DowDebugPanel'};
    if(name.includes('DmiPanel'))return {DmiPanel:'DmiPanel'};
    throw new Error(name);
  }});
  const component=exports.ThreeCandlesWorkspace;render();
  const nodes=()=>{const list=[];function visit(n){if(!n||typeof n!=='object')return;if(Array.isArray(n)){n.forEach(visit);return;}list.push(n);visit(n.props?.children);}visit(tree);return list;};
  return {nodes,analyzedViews,openPanel(id, open=true){
    const panel=nodes().find(n=>n.type==='details'&&n.props?.['data-testid']===id);
    assert.ok(panel,`Missing panel ${id}`);
    const details={open};panel.props.onToggle({target:details,currentTarget:details});render();
  },field:label=>nodes().find(n=>n.props?.label===label),button:label=>nodes().find(n=>n.type==='Button'&&n.props.children===label),
    chart:()=>nodes().find(n=>n.type==='Chart'),setView(v){props={...props,view:v};render();}};
}

test('pattern modes keep one selected candidate, reveal causally, preserve the full audit and isolate E',async()=>{
  const before=patternFixture('1120'),later=patternFixture('1123'),original=JSON.stringify(later),h=host(before);
  assert.equal(h.field('Exibição dos padrões').props.value,'MONITORING');
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns.length,before.patternStudy.candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO'||c.recognizedAtMsc!=null&&c.candle1.candle.bucketStartTimeMsc+900000>before.patternStudy.availableAtMsc).length);
  const chosen=later.patternStudy.candidates.find(c=>c.direction==='BUY'&&c.region.low===170115&&new Date(c.candle1.candle.bucketStartTimeMsc).toISOString().includes('T11:10'));
  h.field('Candidato do padrão (visualização)').props.onChange({target:{value:chosen.id}});await flush();
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns[0].stages.length,2);
  assert.equal(h.chart().props.patternOverlay.executions.length,0);
  assert.ok(h.chart().props.patternViewport);
  h.setView(later);
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns[0].id,chosen.id);
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns[0].stages.length,3);
  assert.equal(h.chart().props.patternOverlay.executions.length,1);
  h.setView(patternFixture('0921'));
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns.length,0);
  assert.equal(h.chart().props.patternOverlay.executions.length,0);
  h.setView(later);
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns[0].id,chosen.id);
  h.field('Exibição dos padrões').props.onChange({target:{value:'TRIGGERS'}});await flush();
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns.length,0);
  assert.equal(h.chart().props.patternOverlay.highlights,false);
  assert.equal(h.chart().props.patternOverlay.executions.length,later.patternStudy.candidates.filter(c=>c.trigger.state==='ALCANCADO_HIPOTETICO').length);
  h.field('Exibição dos padrões').props.onChange({target:{value:'ALL'}});await flush();
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns.length,later.patternAudit.patterns.length);
  h.openPanel('pattern-candidates');
  h.nodes().find(n=>n.type==='tr'&&n.props['aria-selected']===false&&n.props['data-recognized']&&n.props.onClick).props.onClick();await flush();
  assert.equal(h.field('Exibição dos padrões').props.value,'SELECTED');
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns.length,1);
  h.field('Acompanhar candidato mais recente no Replay').props.control.props.onChange(null,true);await flush();
  h.field('Exibição dos padrões').props.onChange({target:{value:'SELECTED'}});await flush();
  assert.equal(h.chart().props.patternOverlay.snapshot.patterns[0].id,later.patternStudy.candidates.filter(c=>later.patternAudit.patterns.some(p=>p.id===c.id)).at(-1).id);
  assert.equal(JSON.stringify(later),original);
  assert.equal(h.nodes().find(n=>n.props?.['data-testid']==='pattern-candidates').props.open,undefined);
});
test('CHOP is off by default and toggling its separate panel cannot mutate recognition or chart layers',async()=>{
  const v=patternFixture('1120');v.chop=JSON.parse(readFileSync(new URL('../src/features/threecandles/validation/chop/1120.json',import.meta.url),'utf8')).chop;
  const before=JSON.stringify(v),h=host(v),overlay=JSON.stringify(h.chart().props);
  assert.equal(h.field('Mostrar CHOP (experimental)').props.control.props.checked,false);assert.ok(!h.nodes().some(n=>n.type==='ChopPanel'));
  h.field('Mostrar CHOP (experimental)').props.control.props.onChange(null,true);await flush();
  assert.ok(h.nodes().some(n=>n.type==='ChopPanel'));assert.equal(JSON.stringify(h.chart().props),overlay);assert.equal(JSON.stringify(v),before);
});

test('chart picks and selectors create an independent manual version; auto advance and reset cannot overwrite it',async()=>{
  const h=host(at('10:25'));
  h.openPanel('advanced-settings');
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

test('all visual layers start off and leave the chart outside every collapsed section',()=>{
  const h=host(at('10:25')),chart=h.chart().props;
  assert.equal(chart.dowOverlay.visible,false);assert.equal(chart.dowOverlay.labels,false);
  assert.equal(chart.dowFiftyOverlay.visible,false);assert.equal(chart.microDowOverlay.visible,false);
  assert.equal(chart.microDowOverlay.labels,false);assert.equal(chart.microDowOverlay.allFractals,false);
  assert.equal(chart.overlay.primary,undefined);assert.equal(chart.overlay.comparison,undefined);
  const panels=h.nodes().filter(n=>n.type==='details');
  assert.ok(panels.length>=3);assert.ok(!h.nodes().some(n=>n.type==='tr'));assert.ok(panels.every(n=>n.props.open!==true));
  const descendants=n=>{const all=[];function visit(v){if(!v||typeof v!=='object')return;if(Array.isArray(v)){v.forEach(visit);return;}all.push(v);visit(v.props?.children);}visit(n);return all;};
  assert.ok(panels.every(p=>!descendants(p).some(n=>n.type==='Chart')));
  assert.ok(h.nodes().find(n=>n.props?.['data-testid']==='advanced-settings'));
  assert.ok(h.nodes().find(n=>n.props?.['data-testid']==='audit-diagnostics'));
});

test('all overlay choices survive snapshots and workspace remounts without touching analytical data',async()=>{
  const view=at('10:25'),before=JSON.stringify(view),presentation={current:{fibonacci:false,dow:true,dowFifty:true,dowLabels:false,micro:false,microLabels:false,allMicroFractals:false}};
  let h=host(view,{presentation});
  h.openPanel('advanced-settings');
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
  h.openPanel('audit-diagnostics');
  h.field('Auditar onda 50% Dow').props.onChange({target:{value:old}});await flush();
  assert.equal(h.chart().props.dowFiftyOverlay.snapshot.currentWaveId,current);
  assert.deepEqual(structuredClone(h.chart().props.overlay),fib);
  h.field('50% Dow principal').props.control.props.onChange({},false);await flush();
  assert.equal(h.chart().props.dowFiftyOverlay.visible,false);
});

test('explicitly enabled checkboxes retain calculation snapshots for every visibility combination',async()=>{
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

test('pattern numbers and outlines start off, retain preferences and never alter the analytical view',async()=>{
  const view=at('10:25'),before=JSON.stringify(view),presentation={current:{fibonacci:false,dow:true,micro:true,dowFifty:true,opening:true}};
  let h=host(view,{presentation});
  const labels=['Mostrar marcações do Padrão de 3 Candles','Mostrar números 1 / 2 / 3','Mostrar destaque colorido dos candles'];
  assert.ok(labels.every(l=>!h.field(l).props.control.props.checked));
  assert.equal(h.chart().props.patternOverlay.visible,false);
  for(let mask=0;mask<8;mask++) {
    for(let i=0;i<3;i++){h.field(labels[i]).props.control.props.onChange(null,Boolean(mask&(1<<i)));await flush();}
    const overlay=h.chart().props.patternOverlay;
    assert.equal(overlay.visible,Boolean(mask&1));assert.equal(overlay.numbers,Boolean(mask&2));assert.equal(overlay.highlights,Boolean(mask&4));
    assert.equal(h.chart().props.dowOverlay.visible,true);assert.equal(h.chart().props.openingOverlay.visible,true);
    assert.equal(JSON.stringify(view),before);
  }
  h.field(labels[1]).props.control.props.onChange(null,false);await flush();
  h=host(view,{presentation});assert.equal(h.chart().props.patternOverlay.numbers,false);assert.equal(h.chart().props.patternOverlay.highlights,true);
  assert.ok(h.nodes().find(n=>n.props?.['data-testid']==='pattern-definition-pending'));
  h.openPanel('audit-diagnostics');
  const audit=h.nodes().find(n=>n.props?.['data-testid']==='pattern-audit');assert.equal(audit.props.open,undefined);
});


test('collapsed diagnostics do not mount tables or analyze hidden Fibonacci, and opening preserves all candidates',()=>{
  const view=patternFixture('1120'),before=JSON.stringify(view),h=host(view);
  assert.equal(h.nodes().filter(n=>n.type==='tr').length,0);
  assert.equal(h.analyzedViews.filter(Boolean).length,0);
  h.openPanel('pattern-candidates');
  assert.ok(h.nodes().filter(n=>n.type==='tr'&&n.props.onClick).length===view.patternStudy.candidates.length);
  assert.equal(h.analyzedViews.filter(Boolean).length,0);
  h.openPanel('pattern-candidates',false);
  assert.equal(h.nodes().filter(n=>n.type==='tr').length,0);
  h.openPanel('advanced-settings');
  assert.equal(h.analyzedViews.filter(Boolean).length,1);
  assert.ok(h.field('Fibonacci'));
  assert.equal(JSON.stringify(view),before);
});
