// Runtime validation against the actual frontend and backend, without API interception.
import {readFile,writeFile,mkdir,mkdtemp} from 'node:fs/promises';
import {join,dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {spawn} from 'node:child_process';

const [outputFile='panic-scanner/target/three-candles-runtime-check.png',pageUrl='http://localhost:5173/three-candles']=process.argv.slice(2);
const profile=await mkdtemp(join(tmpdir(),'three-candles-edge-'));
const browser=spawn(process.env.EDGE_PATH??'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',[
  '--headless=new','--disable-gpu','--no-first-run','--disable-extensions','--remote-debugging-port=0',
  `--user-data-dir=${profile}`,'about:blank',
],{windowsHide:true,stdio:'ignore'});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let socket;
try {
  let port;
  for(let i=0;i<100;i++) {
    try {port=(await readFile(join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];break;}catch{await delay(100);}
  }
  if(!port) throw new Error('Edge DevTools did not start');
  const targets=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
  let sequence=0;const pending=new Map(),errors=[];
  function send(method,params={}) {
    return new Promise((resolve,reject)=>{
      const id=++sequence;
      const timeout=setTimeout(()=>{pending.delete(id);reject(new Error(`CDP timeout: ${method}`));},10000);
      pending.set(id,{resolve:v=>{clearTimeout(timeout);resolve(v);},reject:e=>{clearTimeout(timeout);reject(e);}});
      socket.send(JSON.stringify({id,method,params}));
    });
  }
  socket.onmessage=event=>{
    const message=JSON.parse(event.data);
    if(message.id){const p=pending.get(message.id);pending.delete(message.id);if(p)message.error?p.reject(new Error(JSON.stringify(message.error))):p.resolve(message.result);}
    if(message.method==='Runtime.exceptionThrown')errors.push(message.params.exceptionDetails.text);
  };
  await send('Page.enable');await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride',{width:1520,height:1100,deviceScaleFactor:1,mobile:false});

  await send('Page.navigate',{url:pageUrl});
  const evaluate=async expression=>(await send('Runtime.evaluate',{expression,returnByValue:true})).result?.value;
  async function waitFor(expression){for(let i=0;i<600;i++){if(await evaluate(expression))return;await delay(100);}throw new Error(`Preview condition failed: ${expression}; errors=${errors}`);}
  await waitFor("Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Replay')");
  await waitFor("document.body.textContent.includes('n\u00edveis exibidos') || document.body.textContent.includes('Invalid structure availability')");
  const liveBody=await evaluate('document.body.innerText');
  if(!liveBody.includes('n\u00edveis exibidos'))throw new Error('Live structure rejected');
  await delay(500);
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Replay').click()");
  await waitFor("Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Carregar'&&!b.disabled)");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Carregar').click()");
  await waitFor("document.body.textContent.includes('n\u00edveis exibidos') || document.body.textContent.includes('Invalid structure availability')");
  await delay(1000);
  const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await mkdir(dirname(outputFile),{recursive:true});await writeFile(outputFile,Buffer.from(screenshot.data,'base64'));
  const body=await evaluate('document.body.innerText');
  if(errors.length || !body.includes('Status: PAUSED')) throw new Error(JSON.stringify({errors,body}));
  const report={errors,liveBody,replayBody:body,source:'real frontend and real backend; no interception'};
  await writeFile(outputFile.replace(/\.png$/,'.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({errors,liveValidated:liveBody.includes('n\u00edveis exibidos'),replayValidated:body.includes('Status: PAUSED')}));
  await send('Browser.close').catch(()=>{});
} finally {socket?.close();if(browser.exitCode===null)browser.kill();}
