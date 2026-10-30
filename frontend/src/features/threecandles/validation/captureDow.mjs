import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const base=new URL('dow/',import.meta.url),url=process.argv[2]??'http://127.0.0.1:5173/three-candles';
const template=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const symbol of ['WIN_N','WINZ26'])for(const kind of ['up','down','correction','resistance','reversal']) {
  const file=new URL(`${symbol}-${kind}.json`,base),fixture=JSON.parse(await readFile(file,'utf8'));
  process.argv=['node','capture',fileURLToPath(file),fileURLToPath(new URL(`${symbol}-${kind}.png`,base)),url];
  let source=template.replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:2400')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);');
  source=source.replace("  await send('Page.navigate',{url:pageUrl});",`
  await send('Page.addScriptToEvaluateOnNewDocument',{source:
    "window.dowPoints=[];const original=CanvasRenderingContext2D.prototype.arc;CanvasRenderingContext2D.prototype.arc=function(x,y,r,...args){if(r===3){const b=this.canvas.getBoundingClientRect();if(b.width>300&&x>=0&&y>=0&&x<=b.width&&y<=b.height)window.dowPoints.push({x:b.left+x,y:b.top+y});}return original.call(this,x,y,r,...args)};"});
  await send('Page.navigate',{url:pageUrl});`);
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",begin);assert.ok(begin>0&&end>begin);
  source=source.slice(0,begin)+`
  await waitFor("document.querySelector('[data-testid=dow-state]')?.textContent.includes('${({UP:'ALTA',DOWN:'BAIXA',UNDEFINED:'INDEFINIDO',TRANSITION:'TRANSIÇÃO'})[fixture.dow.state]}')");
  const fibBefore=await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent");
  const toggle=async label=>{await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()==="+JSON.stringify(label)+").querySelector('input').click()");await delay(400);};
  await toggle('Fibonacci');await toggle('Mostrar estrutura Dow');
  await waitFor('window.dowPoints.length>0');
  const point=await evaluate('window.dowPoints.at(-1)');
  await send('Input.dispatchMouseEvent',{type:'mousePressed',x:point.x,y:point.y,button:'left',clickCount:1});
  await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y,button:'left',clickCount:1});
  await waitFor("!!document.querySelector('[data-testid=dow-selected]')");await delay(600);
  const state=await evaluate("document.querySelector('[data-testid=dow-state]').textContent");
  const selected=await evaluate("document.querySelector('[data-testid=dow-selected]').textContent");
  if(fibBefore!==await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent"))throw new Error('Dow altered Fibonacci');
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  const detail={state,selected,errors,fibonacciPreserved:true,source:'Real frontend with Java Dow events over separate MT5 source; API intercepted'};
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify(detail,null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(JSON.stringify({symbol:'${symbol}',kind:'${kind}',state}));
`+source.slice(end);
  try { await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${symbol}-${kind}`).toString('base64')); }
  catch(error){console.error(error.message);process.exitCode=1;throw new Error(`Capture failed for ${symbol}-${kind}`);}
}
