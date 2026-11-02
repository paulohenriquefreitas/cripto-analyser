import { test } from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import * as m5Groups from '../src/features/threecandles/models/m5DisplayGroups.ts';
import * as candleTime from '../src/features/threecandles/models/candleTime.ts';

const flush=async()=>{ for(let i=0;i<12;i++) await Promise.resolve(); };
function host(api) {
  api={archiveM5VisualAudit:async()=>{},...api};
  const states=[],refs=[],effects=[],timers=new Map();
  let stateIndex=0,refIndex=0,effectIndex=0,tree,scheduled=false,disposed=false;
  const chart={ bars:[], fits:0, resets:0, fitChart(){this.fits++;}, grouping:[], setM5Grouping(...args){this.grouping.push(args);}, reset(){this.resets++;this.bars=[];}, apply(v){this.bars=v.candles;}, attachChart(){return()=>{};} };
  let component;
  const schedule=()=>{ if(!scheduled && !disposed){ scheduled=true; queueMicrotask(()=>{scheduled=false; render();}); } };
  const react={
    useState(initial){ const i=stateIndex++; if(!(i in states)) states[i]=typeof initial==='function'?initial():initial;
      return [states[i],value=>{states[i]=typeof value==='function'?value(states[i]):value;schedule();}]; },
    useRef(initial){const i=refIndex++; return refs[i]??(refs[i]={current:initial});},
    useEffect(fn,deps){const i=effectIndex++;const old=effects[i];
      if(!old || deps.some((v,j)=>v!==old.deps[j])) effects[i]={deps,fn,pending:true,cleanup:old?.cleanup}; },
  };
  const mui=new Proxy({}, {get:(_,key)=>key});
  const exports={};
  const source=readFileSync(new URL('../src/features/threecandles/pages/ThreeCandlesPage.tsx',import.meta.url),'utf8');
  const {outputText}=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX}});
  runInNewContext(outputText,{exports,Intl,Date,AbortController,console,Set,
    setTimeout(fn){const id=Symbol();timers.set(id,fn);return id;},clearTimeout(id){timers.delete(id);},
    require(name){
      if(name==='react') return react;
      if(name==='react/jsx-runtime') return {jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})};
      if(name==='@mui/material') return mui;
      if(name.includes('Mt5CandlestickChart')) return {Mt5CandlestickChart:'Chart'};
      if(name.includes('ThreeCandlesWorkspace')) return {ThreeCandlesWorkspace:'Chart'};
      if(name.includes('threeCandlesApi')) return api;
      if(name.includes('candleChartController')) return {createCandleChartController:()=>chart};
      if(name.includes('m5DisplayGroups')) return m5Groups;
      if(name.includes('candleTime')) return candleTime;
      throw new Error(name);
    },
  });
  component=exports.ThreeCandlesPage;
  function render(){stateIndex=refIndex=effectIndex=0;tree=component();
    for(const effect of effects) if(effect.pending){effect.pending=false;effect.cleanup?.();effect.cleanup=effect.fn();}
  }
  const nodes=()=>{const values=[];function visit(node){if(!node || typeof node!=='object')return;
    if(Array.isArray(node)){node.forEach(visit);return;}values.push(node);visit(node.props?.children);
    for(const slot of ['layerControls','supportControls','supportDiagnostics','dataDiagnostics','sessionSummary'])visit(node.props?.[slot]);}
    visit(tree);return values;};
  render();
  return {chart,nodes,
    field:label=>nodes().find(n=>n.props?.label===label),
    button:label=>nodes().find(n=>n.type==='Button'&&String(n.props?.children).includes(label)),
    mode:value=>nodes().find(n=>n.type==='ToggleButtonGroup').props.onChange(null,value),
    async tick(){const callbacks=[...timers.values()];timers.clear();for(const fn of callbacks)void fn();await flush();},
    close(){disposed=true;effects.forEach(e=>e.cleanup?.());},
  };
}
const candle=(index)=>({candle:{symbol:'WINZ26',bucketStartTimeMsc:index*300000,open:100,high:110,low:90,close:100},timeframeMinutes:5,closed:true,availableAtMsc:index*300000+300000});
const config={liveSymbol:'WINZ26',timeframes:[5,15],zone:'America/Sao_Paulo',sessions:[{symbol:'WINZ26',date:'2026-10-23'}]};

test('experimental detection is opt-in and changing it recreates replay with explicit mode',async()=>{
  const requested=[];
  const h=host({config:async()=>config,live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),
    close:async()=>{},start:async(...args)=>{requested.push(args[8]);return {id:'session',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[]};}});
  await flush();h.mode('replay');await flush();
  assert.equal(h.field('Detecção H1').props.value,'STRICT_2_2');
  await h.button('Carregar').props.onClick();await flush();
  h.field('Detecção H1').props.onChange({target:{value:'EXPERIMENTAL_1_1'}});await flush();
  assert.equal(h.chart.bars.length,0);
  await h.button('Carregar').props.onClick();await flush();
  assert.deepEqual(requested,['STRICT_2_2','EXPERIMENTAL_1_1']);h.close();
});

test('date absent from local listing is requested and retained when changing symbol',async()=>{
  let starts=0;
  const h=host({config:async()=>({...config,sessions:[...config.sessions,{symbol:'WIN$N',date:'2026-10-22'}]}),
    live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),close:async()=>{},
    start:async()=>{starts++;return {id:'new',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[]};}});
  await flush();h.mode('replay');await flush();
  h.field('Data').props.onChange({target:{value:'2026-10-26'}});await flush();
  await h.button('Carregar').props.onClick();await flush();
  assert.equal(starts,1);
  h.field('Ativo').props.onChange({target:{value:'WIN$N'}});await flush();
  assert.equal(h.field('Data').props.value,'2026-10-26');h.close();
});

test('page loads live and wires replay controls to the causal session without exposing hidden bars',async()=>{
  const calls=[];let count=0,status='PAUSED';
  const view=()=>({id:'session',symbol:'WINZ26',timeframeMinutes:5,status,speed:1,candles:Array.from({length:count},(_,i)=>candle(i))});
  const h=host({config:async()=>config,live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[candle(0)]}),
    start:async()=>view(),replay:async()=>view(),close:async()=>{},
    command:async(id,action,speed)=>{calls.push([id,action,speed]);if(action==='NEXT')count++;if(action==='RESET')count=0;if(action==='PLAY')status='PLAYING';if(action==='PAUSE')status='PAUSED';return view();},
  });
  await flush();assert.equal(h.chart.bars.length,1);
  assert.equal(h.nodes().find(n=>n.type==='Chart').props.candlesOnly,true);
  assert.equal(h.nodes().find(n=>n.type==='Chart').props.candleDisplayTimeZone,'UTC');
  h.mode('replay');await flush();assert.equal(h.chart.bars.length,0);
  await h.button('Carregar').props.onClick();await flush();assert.equal(h.chart.bars.length,0);
  await h.button('ximo candle').props.onClick();await flush();assert.equal(h.chart.bars.length,1);
  await h.button('Play').props.onClick();await flush();await h.tick();
  await h.button('Pause').props.onClick();await flush();
  await h.field('Velocidade').props.onChange({target:{value:'5'}});await flush();
  await h.button('Reiniciar').props.onClick();await flush();assert.equal(h.chart.bars.length,0);
  assert.deepEqual(calls.map(c=>c[1]),['NEXT','PLAY','PAUSE','SPEED','RESET']);h.close();
});

test('changing timeframe cancels the old live request and ignores its late response',async()=>{
  let resolveOld;const requests=[];
  const h=host({config:async()=>config,close:async()=>{},live:async(symbol,minutes,signal)=>{
    requests.push({symbol,minutes,signal});
    if(minutes===5)return new Promise(resolve=>{resolveOld=resolve;});
    return {symbol,timeframeMinutes:15,status:'LIVE',candles:[]};
  }});
  await flush();assert.equal(requests.length,1);
  h.field('Per\u00edodo').props.onChange({target:{value:'15'}});await flush();
  assert.equal(requests[0].signal.aborted,true);assert.equal(requests[1].minutes,15);
  resolveOld({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[candle(0)]});await flush();
  assert.equal(h.chart.bars.length,0);h.close();
});


test('configuration retries after backend recovery and enables Load without reloading the page',async()=>{
  let attempts=0;
  const h=host({config:async()=>{if(++attempts===1)throw new Error('backend offline');return config;},
    live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),close:async()=>{},
  });
  await flush();h.mode('replay');await flush();
  assert.equal(h.button('Carregar').props.disabled,true);
  await h.tick();
  assert.equal(attempts,2);assert.equal(h.button('Carregar').props.disabled,false);h.close();
});

test('background polling cannot unlock Load while replay preparation is pending',async()=>{
  let finish;
  const h=host({config:async()=>config,live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),
    start:()=>new Promise(resolve=>{finish=resolve;}),close:async()=>{},
  });
  await flush();h.mode('replay');await flush();
  h.button('Carregar').props.onClick();await flush();
  assert.equal(h.button('Carregando replay').props.disabled,true);
  await h.tick();assert.equal(h.button('Carregando replay').props.disabled,true);
  finish({id:'session',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',speed:1,candles:[]});await flush();
  assert.equal(h.button('Carregar').props.disabled,false);h.close();
});

test('switching to replay enables Load even while the old live request has not returned',async()=>{
  const h=host({config:async()=>config,live:()=>new Promise(()=>{}),close:async()=>{}});
  await flush();h.mode('replay');await flush();
  assert.equal(h.button('Carregar').props.disabled,false);h.close();
});

test('line limit is requested from server without resetting replay position',async()=>{
  const limits=[];
  const data={id:'s',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[]};
  const h=host({config:async()=>config,live:async()=>({...data,id:undefined,status:'LIVE'}),
    start:async(...args)=>{limits.push(args[6]);return data;},replay:async(id,signal,max)=>{limits.push(max);return data;},close:async()=>{},
  });
  await flush();h.mode('replay');await flush();h.button('Carregar').props.onClick();await flush();
  await h.tick();
  assert.equal(limits.at(-1),6);assert.equal(h.button('Play').props.disabled,false);
  assert.equal(h.nodes().find(n=>n.type==='Chart').props.structuralLevels,true);
  h.field('Período').props.onChange({target:{value:'15'}});await flush();
  assert.equal(h.nodes().find(n=>n.type==='Chart').props.structuralLevels,false);h.close();
});


test('M5 and H1 start hidden and remain independent without a line quota', async () => {
  const h=host({config:async()=>config,live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),close:async()=>{}});
  await flush();
  const m5=()=>h.field('M5 - amarelo').props.control.props,h1=()=>h.field('H1 - fúcsia').props.control.props;
  assert.equal(m5().checked,false);assert.equal(h1().checked,false);
  assert.equal(h.field('Limite M5'),undefined);
  m5().onChange(null,true);await flush();assert.equal(m5().checked,true);assert.equal(h1().checked,false);
  assert.equal(h.field('Limite M5'),undefined);
  h.close();
});


test('M5 grouping defaults to 30 points and updates locally without restarting Replay',async()=>{
  let starts=0,closes=0;
  const data={id:'s',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[]};
  const h=host({config:async()=>config,live:async()=>({...data,id:undefined,status:'LIVE'}),
    start:async()=>{starts++;return data;},close:async()=>{closes++;},replay:async()=>data});
  await flush();h.mode('replay');await flush();await h.button('Carregar').props.onClick();await flush();
  assert.equal(h.field('Agrupamento M5 (pontos)').props.value,30);
  for(const value of [15,50,0,30]) {
    h.field('Agrupamento M5 (pontos)').props.onChange({target:{value:String(value)}});await flush();
    assert.equal(h.chart.grouping.at(-1)[0],value);
  }
  assert.equal(h.field('Separar movimentos apos rompimento'),undefined);
  assert.equal(starts,1);assert.equal(closes,0);assert.equal(h.button('Play').props.disabled,false);
  h.close();
});


test('Fit button is local and RESET clears viewport identity before applying the restored replay',async()=>{
  const calls=[];
  const data={id:'s',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[candle(0)]};
  const h=host({config:async()=>config,live:async()=>({...data,id:undefined,status:'LIVE'}),close:async()=>{},
    start:async()=>data,command:async(id,action)=>{calls.push(action);return data;}});
  await flush();h.mode('replay');await flush();await h.button('Carregar').props.onClick();await flush();
  assert.equal(h.nodes().find(n=>n.type==='Chart').props.visibleCandleViewport,true);
  h.button('Ajustar gr\u00e1fico').props.onClick();assert.equal(h.chart.fits,1);assert.deepEqual(calls,[]);
  const resets=h.chart.resets;
  await h.button('ximo candle').props.onClick();await flush();assert.equal(h.chart.resets,resets);
  await h.button('Reiniciar').props.onClick();await flush();assert.equal(h.chart.resets,resets+1);
  assert.equal(h.chart.bars.length,1);h.close();
});


test('progressive Replay is opt-in, resets the session on mode change and sends the selected source',async()=>{
  const requested=[];
  const h=host({config:async()=>config,live:async()=>({symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[]}),
    close:async()=>{},start:async(...args)=>{requested.push(args[9]);return {id:'session',symbol:'WINZ26',timeframeMinutes:5,status:'PAUSED',candles:[]};}});
  await flush();h.mode('replay');await flush();
  assert.equal(h.field('Modo de Replay').props.value,'CLOSED_CANDLE');
  await h.button('Carregar').props.onClick();await flush();
  h.field('Modo de Replay').props.onChange({target:{value:'PROGRESSIVE_M1'}});await flush();
  assert.equal(h.chart.bars.length,0);
  await h.button('Carregar').props.onClick();await flush();
  assert.deepEqual(requested,['CLOSED_CANDLE','PROGRESSIVE_M1']);h.close();
});

const textOf=node=>node==null?'':Array.isArray(node)?node.map(textOf).join(' '):typeof node==='object'?textOf(node.props?.children):String(node);

test('primary status distinguishes replay instant, candle origin, and finished coverage',async()=>{
  const data={id:'s',symbol:'WINZ26',timeframeMinutes:5,status:'COMPLETED',availableAtMsc:1793181540000,
    candles:[{candle:{symbol:'WINZ26',bucketStartTimeMsc:1793181300000,open:100,high:110,low:90,close:105},timeframeMinutes:5,closed:false,availableAtMsc:1793181540000}]};
  const h=host({config:async()=>config,live:async()=>({...data,id:undefined}),start:async()=>data,close:async()=>{}});
  await flush();h.mode('replay');await flush();await h.button('Carregar').props.onClick();await flush();
  const summary=textOf(h.nodes().find(n=>n.props?.['data-testid']==='session-summary'));
  assert.ok(summary.includes('FINISHED'));assert.ok(summary.includes('Instante do Replay'));
  assert.ok(summary.includes('09:59:00'));assert.ok(summary.includes('09:55:00'));assert.ok(summary.includes('Em formação'));
  assert.equal(data.status,'COMPLETED','presentation must not rewrite transport status');h.close();
});

test('primary alerts retain missing M1, unavailable source and incompatible backend diagnostics',async()=>{
  for(const [extra,expected] of [
    [{status:'UNAVAILABLE'},'Stream de ticks indisponível'],
    [{structure:undefined},'backend ainda não enviou'],
    [{replayMode:'PROGRESSIVE_M1',missingM1Times:[1793199660000]},'minuto(s) ausente(s)'],
    [{structure:{supported:true,levels:[],totalActive:0,maxLevels:6,selection:'NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED'}},'Preparação H1 indisponível'],
  ]) {
    const data={symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',candles:[],...extra};
    const h=host({config:async()=>config,live:async()=>data,close:async()=>{}});await flush();
    const alerts=textOf(h.nodes().find(n=>n.props?.['data-testid']==='session-alerts'));
    assert.ok(alerts.includes(expected),alerts);h.close();
  }
});

test('M15 clearly disables structural layers while preserving the previous presentation choices',async()=>{
  const h=host({config:async()=>config,live:async(symbol,minutes)=>({symbol,timeframeMinutes:minutes,status:'LIVE',candles:[]}),close:async()=>{}});
  await flush();h.field('M5 - amarelo').props.control.props.onChange(null,true);await flush();
  h.field('Período').props.onChange({target:{value:'15'}});await flush();
  assert.equal(h.field('M5 - amarelo').props.control.props.disabled,true);
  assert.equal(h.field('M5 - amarelo').props.control.props.checked,true);
  assert.ok(textOf(h.nodes().find(n=>n.props?.['data-testid']==='session-alerts')).includes('somente em M5'));
  h.field('Período').props.onChange({target:{value:'5'}});await flush();
  assert.equal(h.field('M5 - amarelo').props.control.props.disabled,false);
  assert.equal(h.field('M5 - amarelo').props.control.props.checked,true);h.close();
});

test('all H1 audit sections start collapsed even when the experimental comparison is present',async()=>{
  const view=JSON.parse(readFileSync(new URL('./fixtures/threeCandlesExperimentalH1.json',import.meta.url),'utf8'));
  const h=host({config:async()=>config,live:async()=>({...view,id:undefined,status:'LIVE'}),close:async()=>{}});
  await flush();const details=h.nodes().filter(n=>n.type==='details');
  assert.ok(details.some(n=>textOf(n.props.children[0]).includes('Candidatos H1 1+1')));
  assert.ok(details.every(n=>n.props.open!==true));
  assert.equal(h.field('Referência H1 2+2').props.control.props.checked,false,'comparison visibility starts off');
  assert.equal(h.field('Adicionais H1 1+1').props.control.props.checked,false);
  assert.equal(h.field('H1 - fúcsia').props.control.props.checked,false);h.close();
});


test('M5 audit archives the single visual projection once and polling does not restart the feed',async()=>{
  const calls=[];
  const levels=[{id:'WINZ26:5:300000:TOP',type:'TOP',price:100,timeframeMinutes:5,extremeTimeMsc:300000,confirmedAtMsc:1200000},
    {id:'WINZ26:5:600000:BOTTOM',type:'BOTTOM',price:125,timeframeMinutes:5,extremeTimeMsc:600000,confirmedAtMsc:1500000}];
  const data={symbol:'WINZ26',timeframeMinutes:5,status:'LIVE',availableAtMsc:1800000,candles:[],structure:{levels}};
  const h=host({config:async()=>config,live:async()=>data,archiveM5VisualAudit:async(...args)=>calls.push(args)});
  await flush();
  assert.equal(calls.length,1);assert.equal(calls[0][2],30);
  assert.equal(calls[0][4][0].oldPivot.id,levels[0].id);
  assert.equal(calls[0][4][0].newPivot.id,levels[1].id);
  assert.equal(calls[0][4][0].distancePoints,25);
  await h.tick();assert.equal(calls.length,1);
  assert.equal(h.chart.bars,data.candles);
  h.close();
});


test('paused and completed replay do not repeatedly fetch or render snapshots; play and step remain causal',async()=>{
  let polls=0,status='PAUSED',count=0;
  const view=()=>({id:'session',symbol:'WINZ26',timeframeMinutes:5,status,speed:1,candles:Array.from({length:count},(_,i)=>candle(i))});
  const h=host({config:async()=>config,live:async()=>({...view(),id:undefined,status:'LIVE'}),close:async()=>{},
    start:async()=>view(),replay:async()=>{polls++;return view();},
    command:async(_id,action)=>{if(action==='PLAY')status='PLAYING';if(action==='PAUSE')status='PAUSED';if(action==='NEXT')count++;return view();}});
  await flush();h.mode('replay');await flush();await h.button('Carregar').props.onClick();await flush();
  await h.tick();await h.tick();assert.equal(polls,0);
  await h.button('ximo candle').props.onClick();await flush();assert.equal(h.chart.bars.length,1);
  await h.button('Play').props.onClick();await flush();await h.tick();assert.equal(polls,1);
  await h.button('Pause').props.onClick();await flush();await h.tick();assert.equal(polls,1);
  await h.button('Play').props.onClick();await flush();status='COMPLETED';await h.tick();assert.equal(polls,2);
  await h.tick();assert.equal(polls,2);h.close();
});
