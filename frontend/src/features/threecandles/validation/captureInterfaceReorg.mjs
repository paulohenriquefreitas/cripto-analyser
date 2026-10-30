// Real React + native chart in Edge. Recorded Java snapshots remain separate by symbol/date.
// All API responses are intercepted. This never starts a terminal, strategy or real replay.
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {spawn} from 'node:child_process';

const root=new URL('interface-reorg/',import.meta.url);
const kind=process.argv[2]??'WINZ26';
const file=kind==='WIN_N'?'../dow-fifty/WIN_N-final.json':kind==='PROGRESSIVE'?'../progressive/WINZ26-0959.json':'../dow-fifty/WINZ26-touch.json';
let fixture=JSON.parse(await readFile(new URL(file,root),'utf8'));
if(kind==='WIN_N') {
  const prepared=JSON.parse(await readFile(new URL('../fibonacci/replay.json',root),'utf8'));
  fixture={...prepared,dow:fixture.dow,dowFifty:fixture.dowFifty};
}
if(fixture.symbol==='WINZ26') {
  const micro=JSON.parse(await readFile(new URL('../oct28/prefixes.json',root),'utf8')).find(p=>p.atMsc===Math.floor(fixture.availableAtMsc/300000)*300000)?.micro;
  if(micro)fixture.microDow={...micro,availableAtMsc:fixture.availableAtMsc};
}
fixture={...fixture,id:'ui-audit-session',status:'PAUSED',speed:1,replayMode:kind==='PROGRESSIVE'?'PROGRESSIVE_M1':'CLOSED_CANDLE'};
const m15=[];
for(const bar of fixture.candles.filter(c=>c.closed)){
  const t=Math.floor(bar.candle.bucketStartTimeMsc/900000)*900000;
  const group=fixture.candles.filter(c=>c.closed&&c.candle.bucketStartTimeMsc>=t&&c.candle.bucketStartTimeMsc<t+900000);
  if(group.length!==3||group.some((c,i)=>c.candle.bucketStartTimeMsc!==t+i*300000)||m15.some(c=>c.candle.bucketStartTimeMsc===t))continue;
  m15.push({timeframeMinutes:15,closed:true,availableAtMsc:t+900000,candle:{symbol:fixture.symbol,bucketStartTimeMsc:t,open:group[0].candle.open,high:Math.max(...group.map(c=>c.candle.high)),low:Math.min(...group.map(c=>c.candle.low)),close:group[2].candle.close}});
}
const m15Fixture={id:'ui-audit-m15',symbol:fixture.symbol,timeframeMinutes:15,status:'PAUSED',speed:1,replayMode:'CLOSED_CANDLE',availableAtMsc:fixture.availableAtMsc,candles:m15,
  structure:{levels:[],confirmedPivots:[],supported:false,maxLevels:6,totalActive:0,selection:'NEAREST_LAST_CLOSED_THEN_LATEST_CONFIRMED'}};
const progressiveNext=kind==='PROGRESSIVE'?{...JSON.parse(await readFile(new URL('../progressive/WINZ26-1000.json',root),'utf8')),id:fixture.id}:null;
await mkdir(root,{recursive:true});
await writeFile(new URL(kind+'-fixture.json',root),JSON.stringify(fixture,null,2));
const before=JSON.stringify(fixture);
const profile=await mkdtemp(join(tmpdir(),'three-candles-ui-'));
const browser=spawn(process.env.EDGE_PATH??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',[
  '--headless=new','--disable-gpu','--no-first-run','--disable-extensions','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank',
],{windowsHide:true,stdio:'ignore'});
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let socket;
try {
  let port;for(let i=0;i<300;i++){try{port=(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];break;}catch{await delay(100);}}
  if(!port)throw new Error('Edge did not start');
  const targets=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j;});
  let sequence=0;const pending=new Map(),errors=[],commands=[],requests=[];let creates=0,failNext=false,cancelledInterceptions=0,activeFixture=fixture;
  const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;const timer=setTimeout(()=>{pending.delete(id);reject(new Error('CDP timeout '+method));},60000);pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v);},reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true});if(r.exceptionDetails)throw new Error(r.exceptionDetails.text+': '+expression);return r.result?.value;};
  const check=(condition,message)=>{if(!condition)throw new Error(message);};
  const waitFor=async expression=>{for(let i=0;i<400;i++){if(await evaluate(expression))return;await delay(100);}throw new Error('Condition failed '+expression+'; errors='+errors+'; body='+await evaluate('document.body.textContent.slice(0,5000)'));};
  socket.onmessage=async event=>{
    const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);}
    if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);
    if(m.method!=='Fetch.requestPaused')return;
    const req=m.params,path=new URL(req.request.url).pathname;let status=200,contentType='application/json',data;
    if(path.includes('ThreeCandlesChart.tsx')) {
      contentType='application/javascript';data=await(await fetch(req.request.url)).text();
      const modified=data.replace('const series = chart.addSeries','window.__uiChart=chart;window.__uiCreations=(window.__uiCreations??0)+1; const series = chart.addSeries')
        .replace('const m5 = new SwingPivotPrimitive','window.__uiSeries=series; const m5 = new SwingPivotPrimitive')
        .replace('chart.subscribeClick(', 'window.__uiClickState=state; chart.subscribeClick(');
      check(modified!==data,'Chart instrumentation failed');data=modified;
    } else {
      requests.push({path,method:req.request.method});
      data=activeFixture;
      if(path.endsWith('/config'))data={liveSymbol:fixture.symbol,timeframes:[5,15],zone:'America/Sao_Paulo',sessions:[{symbol:fixture.symbol,date:new Date(fixture.availableAtMsc).toISOString().slice(0,10)}]};
      if(path.endsWith('/history/status'))data={cached:true};
      if(path.endsWith('/live'))data={...fixture,id:undefined,status:'LIVE',replayMode:undefined};
      if(path.endsWith('/replays')&&req.request.method==='POST'){
        creates++;activeFixture=JSON.parse(req.request.postData).timeframeMinutes===15?m15Fixture:fixture;data=activeFixture;
      }
      if(path.endsWith('/commands')&&req.request.method==='POST') {
        const command=JSON.parse(req.request.postData);commands.push(command.action);
        if(failNext){status=503;data={detail:'Fixture: histórico M1 indisponível para este corte. Nenhum candle artificial foi criado.'};failNext=false;}
        else {
          if(command.action==='NEXT'&&progressiveNext)activeFixture=progressiveNext;
          if(command.action==='RESET')activeFixture=fixture;
          data={...activeFixture,status:command.action==='PLAY'?'PLAYING':command.action==='PAUSE'?'PAUSED':activeFixture.status,speed:command.speed??activeFixture.speed};
        }
      }
    }
    await send('Fetch.fulfillRequest',{requestId:req.requestId,responseCode:status,responseHeaders:[{name:'Content-Type',value:contentType},{name:'Access-Control-Allow-Origin',value:'*'},{name:'Access-Control-Allow-Methods',value:'GET,POST,DELETE,OPTIONS'},{name:'Access-Control-Allow-Headers',value:'Content-Type'}],body:Buffer.from(contentType==='application/json'?JSON.stringify(data):data).toString('base64')}).catch(e=>{if(e.message.includes('Invalid InterceptionId'))cancelledInterceptions++;else errors.push(e.message);});
  };
  await send('Page.enable');await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride',{width:1600,height:1200,deviceScaleFactor:1,mobile:false});
  await send('Fetch.enable',{patterns:[{urlPattern:'*/api/three-candles/*'},{urlPattern:'*ThreeCandlesChart.tsx*'}]});
  void send('Page.navigate',{url:'http://127.0.0.1:5174/three-candles'});
  const clickButton=async text=>{await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim().includes(${JSON.stringify(text)})).click()`);await delay(250);};
  const section=async(text,open)=>{await evaluate(`(()=>{const s=Array.from(document.querySelectorAll('summary')).find(s=>s.textContent.trim()===${JSON.stringify(text)});if(!s)throw Error('Missing summary');if(s.parentElement.open!==${open})s.click()})()`);await delay(150);};
  const box=async(label,value)=>{await evaluate(`(()=>{const l=Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()===${JSON.stringify(label)});if(!l)throw Error('Missing checkbox');const i=l.querySelector('input');if(i.checked!==${value})i.click()})()`);await delay(100);};
  const select=async(label,option)=>{
    await evaluate(`(()=>{const field=Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()===${JSON.stringify(label)});const id=field.htmlFor;document.getElementById(id).dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))})()`);
    await waitFor(`Array.from(document.querySelectorAll('[role=option]')).some(o=>o.textContent.includes(${JSON.stringify(option)}))`);
    await evaluate(`Array.from(document.querySelectorAll('[role=option]')).find(o=>o.textContent.includes(${JSON.stringify(option)})).click()`);await delay(200);
  };
  const shot=async name=>{await delay(300);const r=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile(new URL(kind+'-'+name+'.png',root),Buffer.from(r.data,'base64'));};
  await waitFor("Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Replay')");
  await waitFor("document.querySelector('[data-testid=session-summary]')?.textContent.includes('LIVE')");
  check(await evaluate("!Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Carregar')"),'Live shows replay load command');
  await shot('live');
  await clickButton('Replay');await waitFor("Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Carregar'&&!b.disabled)");
  if(kind==='PROGRESSIVE')await select('Modo de Replay','Replay progressivo M1');
  await clickButton('Carregar');await waitFor("!!document.querySelector('[data-testid=session-summary]')?.textContent.includes('PAUSED') && !!window.__uiChart");
  const defaults=await evaluate("Object.fromEntries(Array.from(document.querySelectorAll('label')).filter(l=>l.querySelector('input[type=checkbox]')).map(l=>[l.textContent.trim(),l.querySelector('input').checked]))");
  for(const label of ['Mostrar Dow principal','Mostrar 50% Dow principal'])check(defaults[label]===true,'Default should be on '+label);
  for(const label of ['M5 - amarelo','H1 - fucsia','Mostrar etiquetas HH/HL/LH/LL','Fibonacci','Mostrar ondas Dow micro (1+1 experimental)','Mostrar todos os fractais micro confirmados','Mostrar etiquetas HH/HL/LH/LL micro'])check(defaults[label]===false,'Default should be off '+label);
  check(await evaluate("Array.from(document.querySelectorAll('details')).every(d=>!d.open)"),'Panels initially expanded');
  if(kind!=='WIN_N')await evaluate('window.__uiChart.timeScale().fitContent()');
  await shot(kind==='PROGRESSIVE'?'progressive':'main');
  const chartTop=await evaluate("document.querySelector('[role=img]').getBoundingClientRect().top");check(chartTop<700,'Chart buried below controls');
  await evaluate('window.__uiChart.timeScale().setVisibleLogicalRange({from:1,to:9});window.__uiChart.priceScale("right").applyOptions({autoScale:false})');
  await delay(350);
  const viewport=await evaluate('({range:window.__uiChart.timeScale().getVisibleLogicalRange(),count:window.__uiCreations})');
  const replayCursor=await evaluate("document.querySelector('[data-testid=session-summary]').textContent");
  const startCreates=creates;
  await section('Configurações avançadas',true);await section('Estrutura de Dow',true);
  await evaluate("document.querySelector('[data-testid=advanced-settings]').scrollIntoView({block:'start'})");await shot('advanced');
  await section('Estrutura de Dow',false);await section('Configurações avançadas',false);
  await section('Auditoria e diagnósticos',true);await section('Dow principal',true);
  await evaluate("document.querySelector('[data-testid=audit-diagnostics]').scrollIntoView({block:'start'})");await shot('audit');
  await section('Dow principal',false);await section('Auditoria e diagnósticos',false);
  const afterPanels=await evaluate('({range:window.__uiChart.timeScale().getVisibleLogicalRange(),count:window.__uiCreations,auto:window.__uiChart.priceScale("right").options().autoScale})');
  check(JSON.stringify(afterPanels.range)===JSON.stringify(viewport.range)&&afterPanels.count===viewport.count&&!afterPanels.auto,'Panels changed native viewport or chart: '+JSON.stringify({before:viewport,after:afterPanels}));
  check(creates===startCreates,'Panels recreated session');
  check(await evaluate("document.querySelector('[data-testid=session-summary]').textContent")===replayCursor,'Panels changed replay cursor');
  check(await evaluate("Array.from(document.querySelectorAll('input:not([aria-hidden=true])')).every(i=>i.labels?.length || i.getAttribute('aria-label') || i.getAttribute('aria-labelledby'))"),'Unlabelled interactive input');
  await evaluate("(()=>{const s=document.querySelector('[data-testid=advanced-settings]>summary');s.focus()})()");
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:' ',code:'Space',windowsVirtualKeyCode:32});await send('Input.dispatchKeyEvent',{type:'keyUp',key:' ',code:'Space',windowsVirtualKeyCode:32});
  await waitFor("document.querySelector('[data-testid=advanced-settings]').open");await section('Configurações avançadas',false);
  for(const label of Object.keys(defaults)){await box(label,!defaults[label]);await box(label,defaults[label]);}
  check(creates===startCreates,'Layer changes recreated session');
  await evaluate('window.__uiChart.timeScale().fitContent();window.__uiChart.priceScale("right").applyOptions({autoScale:true});window.scrollTo(0,0)');await delay(500);
  const current=fixture.dowFifty.references.find(r=>r.wave.id===fixture.dowFifty.currentWaveId);
  const mouseClick=async point=>{await send('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});await send('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});};
  if(current){
    const point=await evaluate(`(()=>{const c=window.__uiChart,s=window.__uiSeries,r=document.querySelector('[role=img]').getBoundingClientRect();const a=c.timeScale().timeToCoordinate(${current.wave.initial.extremeTimeMsc/1000}),b=c.timeScale().timeToCoordinate(${current.wave.terminal.extremeTimeMsc/1000});return{x:r.left+Math.max(0,(a+b)/2)+(c.timeScale().width()-Math.max(0,(a+b)/2))/2,y:r.top+s.priceToCoordinate(${current.wave.level50})}})()`);
    await mouseClick(point);await waitFor("!!document.querySelector('[data-testid=dow-fifty-selected]')");
    await evaluate("document.querySelector('[data-testid=dow-fifty-selected]').scrollIntoView({block:'center'})");await shot('fifty-details');
    await clickButton('Fechar detalhes 50%');
  }
  await evaluate("document.querySelector('[role=img]').scrollIntoView({block:'center'})");
  const pivot=fixture.dow.pivots.find(p=>p.extreme.price!==current?.wave.level50);
  const p=await evaluate(`(()=>{const c=window.__uiChart,s=window.__uiSeries,r=document.querySelector('[role=img]').getBoundingClientRect();return{x:r.left+c.timeScale().timeToCoordinate(${pivot.extreme.extremeTimeMsc/1000}),y:r.top+s.priceToCoordinate(${pivot.extreme.price})}})()`);
  await mouseClick(p);await waitFor("!!document.querySelector('[data-testid=dow-selected]')");
  await clickButton('Fechar detalhe do pivô');
  await box('H1 - fucsia',true);await box('M5 - amarelo',true);await box('Mostrar Dow principal',false);
  const commandPreferences={...defaults,'H1 - fucsia':true,'M5 - amarelo':true,'Mostrar Dow principal':false};
  await clickButton('Play');await clickButton('Pause');await clickButton(kind==='PROGRESSIVE'?'Próximo M1':'Próximo candle');
  if(kind==='PROGRESSIVE'){
    await waitFor("document.querySelector('[data-testid=session-summary]').textContent.includes('10:00:00')");
    check(await evaluate("document.querySelector('[data-testid=session-summary]').textContent.includes('Fechado')"),'Partial M5 did not close at next native M1');
    await shot('progressive-closed');
  }
  await select('Velocidade','5x');await clickButton('Reiniciar');
  check(['PLAY','PAUSE','NEXT','SPEED','RESET'].every(c=>commands.includes(c)),'Replay commands missing');
  for(const [label,value] of Object.entries(commandPreferences))check(await evaluate(`Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()===${JSON.stringify(label)}).querySelector('input').checked`)===value,'Command reset preference '+label);
  for(const [label,value] of Object.entries(defaults))await box(label,value);
  await section('Configurações avançadas',true);await section('Fibonacci antiga / Comparação manual',true);await box('Fibonacci',true);
  await clickButton('Selecionar máxima no gráfico');check(await evaluate('window.__uiClickState.current.overlay.pick')==='TOP','Manual picking not armed');
  await clickButton('Cancelar captura');check(await evaluate('window.__uiClickState.current.overlay.pick')==null,'Manual picking not cancelled');
  const anchors=await evaluate('window.__uiClickState.current.overlay.anchors');
  const top=anchors.find(a=>a.type==='TOP'),bottom=anchors.find(a=>a.type==='BOTTOM'&&a.price<top.price&&a.extremeTimeMsc!==top.extremeTimeMsc);
  await select('Máxima manual confirmada',top.price.toLocaleString('pt-BR'));
  await select('Mínima manual confirmada',bottom.price.toLocaleString('pt-BR'));
  await clickButton('Aplicar Fibonacci manual');check(await evaluate('window.__uiClickState.current.overlay.primary.mode')==='MANUAL','Manual not applied');
  await section('Fibonacci antiga / Comparação manual',false);await section('Configurações avançadas',false);await box('Fibonacci',false);
  if(kind==='WINZ26') {
    failNext=true;await clickButton('Próximo candle');await waitFor("document.body.textContent.includes('Fixture: histórico M1 indisponível')");await evaluate('window.scrollTo(0,0)');await shot('data-error');
  }
  await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await evaluate('window.scrollTo(0,0)');await delay(500);
  check(await evaluate('document.documentElement.scrollWidth<=window.innerWidth+1'),'Mobile horizontal overflow');await shot('mobile');
  await select('Período','M15');await waitFor("document.body.textContent.includes('disponíveis somente em M5')");
  check(await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()==='Mostrar Dow principal').querySelector('input').disabled"),'M15 Dow control enabled');
  await clickButton('Carregar');await waitFor("document.querySelector('[data-testid=session-summary]')?.textContent.includes('Candle M15') && document.querySelector('[data-testid=session-summary]')?.textContent.includes('PAUSED')");
  check(await evaluate('window.__uiClickState.current.dowOverlay.visible===false && window.__uiClickState.current.dowFiftyOverlay.visible===false'),'M15 draws unsupported structure');
  await shot('m15');
  await select('Período','M5');
  check(before===JSON.stringify(fixture),'Recorded snapshot mutated');check(errors.length===0,'Browser errors '+errors);
  const evidence={kind,symbol:fixture.symbol,atMsc:fixture.availableAtMsc,defaults,chartTop,viewportPreserved:true,keyboardExpansion:true,
    layerIndependence:true,manualApplied:true,dowClick:true,fiftyClick:!!current,commands,creates,errors,cancelledInterceptions,mobileNoOverflow:true,snapshotUnchanged:true,liveM5:true,m15Candles:true,progressiveClosure:!!progressiveNext,replayCursorPreserved:true,inputsLabelled:true,
    source:'Real React/native chart and intercepted recorded Java snapshots; error is a labelled HTTP fixture'};
  await writeFile(new URL(kind+'-browser.json',root),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence));
  await send('Browser.close').catch(()=>{});
} finally {socket?.close();if(browser.exitCode===null)browser.kill();}
