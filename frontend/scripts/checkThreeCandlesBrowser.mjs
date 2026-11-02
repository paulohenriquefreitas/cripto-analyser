// Isolated real-browser check. Every market API request is fulfilled from a recorded fixture.
import { readFile, writeFile, mkdtemp, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';

const fixture=JSON.parse(await readFile(new URL('../src/features/threecandles/validation/pattern-detector/1155.json',import.meta.url),'utf8'));
const live={...fixture,id:undefined,status:'LIVE'};
const profile=await mkdtemp(join(tmpdir(),'three-candles-performance-'));
const browser=spawn(process.env.EDGE_PATH??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',[
  '--headless=new','--disable-gpu','--no-first-run','--disable-extensions','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank',
],{windowsHide:true,stdio:['ignore','ignore','pipe']});
let startupError='';
browser.stderr.on('data',chunk=>{startupError+=chunk.toString();});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let socket;
try {
  let port;
  for(let i=0;i<300;i++){try{port=(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];break;}catch{if(browser.exitCode!==null)break;await delay(100);}}
  if(!port)throw Error(`Browser did not start (${browser.exitCode}): ${startupError.slice(0,1000)}`);
  const targets=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket=new WebSocket(targets.find(target=>target.type==='page').webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  let sequence=0;
  const pending=new Map(),errors=[];
  const send=(method,params={})=>new Promise((resolve,reject)=>{
    const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error(`Timeout: ${method}`));},30000);
    pending.set(id,{resolve:value=>{clearTimeout(timer);resolve(value);},reject:error=>{clearTimeout(timer);reject(error);}});
    socket.send(JSON.stringify({id,method,params}));
  });
  socket.onmessage=async event=>{
    const message=JSON.parse(event.data);
    if(message.id){const request=pending.get(message.id);pending.delete(message.id);if(request)message.error?request.reject(Error(JSON.stringify(message.error))):request.resolve(message.result);return;}
    if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails.text);
    if(message.method!=='Fetch.requestPaused')return;
    const request=message.params,path=new URL(request.request.url).pathname;
    const data=path.endsWith('/config')?{liveSymbol:fixture.symbol,timeframes:[5,15],zone:'America/Sao_Paulo',sessions:[]}:live;
    await send('Fetch.fulfillRequest',{requestId:request.requestId,responseCode:200,responseHeaders:[
      {name:'Content-Type',value:'application/json'},{name:'Access-Control-Allow-Origin',value:'*'},
      {name:'Access-Control-Allow-Headers',value:'*'},{name:'Access-Control-Allow-Methods',value:'GET,POST,OPTIONS,DELETE'},
    ],body:Buffer.from(JSON.stringify(data)).toString('base64')}).catch(error=>errors.push(error.message));
  };
  const evaluate=async expression=>{
    const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
    if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await send('Page.enable');await send('Runtime.enable');
  await send('Fetch.enable',{patterns:[{urlPattern:'*/api/three-candles/*'}]});
  await send('Page.navigate',{url:process.env.THREE_CANDLES_URL??'http://localhost:5175/three-candles'});
  let ready=false;
  for(let i=0;i<150;i++){
    if(await evaluate("document.querySelector('[data-testid=pattern-candidates]')?.textContent.includes('('+"+fixture.patternStudy.candidates.length+"+')')")){ready=true;break;}
    await delay(100);
  }
  if(!ready)throw Error('Workspace did not load: '+await evaluate('document.body.textContent.slice(0,2000)'));
  const closed=await evaluate(`({rows:document.querySelectorAll('tr').length,auditRows:document.querySelectorAll('[data-testid=pattern-candidates] tr,[data-testid=advanced-settings] tr,[data-testid=audit-diagnostics] tr').length,checked:document.querySelectorAll('input[type=checkbox]:checked').length,
    canvases:document.querySelectorAll('canvas').length,candidateOptions:document.querySelector('select')?.options.length})`);
  if(closed.auditRows!==0||closed.checked!==0||closed.canvases===0)throw Error('Unexpected initial DOM: '+JSON.stringify(closed));
  await evaluate("document.querySelector('[data-testid=pattern-candidates] > summary').click()");
  await delay(300);
  const openedRows=await evaluate("document.querySelectorAll('[data-testid=pattern-candidates] tbody tr').length");
  if(openedRows!==fixture.patternStudy.candidates.length)throw Error('Candidate audit lost rows');
  await evaluate("document.querySelector('[data-testid=pattern-candidates] > summary').click()");
  await delay(300);
  const closedAgainRows=await evaluate("document.querySelectorAll('[data-testid=pattern-candidates] tr').length");
  if(closedAgainRows!==0)throw Error('Closed candidate table remained mounted');
  const report={source:'Real Edge, recorded API fixture, no backend/MT5 calls',candidates:fixture.patternStudy.candidates.length,closed,openedRows,closedAgainRows,errors};
  if(errors.length)throw Error(JSON.stringify(errors));
  await mkdir(new URL('../../docs/three-candles-performance/',import.meta.url),{recursive:true});
  await writeFile(new URL('../../docs/three-candles-performance/browser.json',import.meta.url),JSON.stringify(report,null,2));
  console.log(report);
} finally {
  socket?.close();
  if(browser.exitCode===null)browser.kill();
}
