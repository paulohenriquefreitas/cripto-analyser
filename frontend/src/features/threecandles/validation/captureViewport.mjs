// Controlled browser validation: real chart/library, Java Replay snapshots, intercepted API.
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const out = fileURLToPath(new URL('viewport/', import.meta.url));
await mkdir(out, { recursive: true });
const fixturePath = fileURLToPath(new URL('../../../../../panic-scanner/target/threecandles-m5-validation/1000.json', import.meta.url));
process.argv = ['node', 'capture', fixturePath, out + 'initial.png', process.argv[2] ?? 'http://localhost:5173/three-candles'];
let source = await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs', import.meta.url), 'utf8');
source = source.replace("const fixture=JSON.parse(await readFile(fixtureFile,'utf8'));", `
const full=JSON.parse(await readFile(fixtureFile,'utf8'));
const opening=Date.parse('2026-10-16T09:00:00Z');
function prefix(time) {
  return {...full,availableAtMsc:time,candles:full.candles.filter(c=>c.availableAtMsc<=time),structure:{...full.structure,
    levels:full.structure.levels.filter(l=>l.confirmedAtMsc<=time).map(l=>l.timeframeMinutes===5&&l.brokenAtMsc>time?{...l,state:'CONFIRMED',brokenAtMsc:null}:l),
    confirmedPivots:full.structure.confirmedPivots?.filter(l=>l.confirmedAtMsc<=time)}};
}
let fixture=prefix(opening);
`);
source = source.replace('height:fixture.structure?.preparation?.experimental?1480:1100', 'height:1900');
source = source.replace('      let data=fixture;', `
      if(path.endsWith('/commands')) {
        const action=JSON.parse(request.request.postData||'{}').action;
        if(action==='RESET')fixture=prefix(opening);
        if(action==='NEXT')fixture=prefix(Math.min(fixture.availableAtMsc+300000,full.availableAtMsc));
      }
      let data=fixture;`);
// Observe price-axis labels painted by the native library, without changing rendering.
source = source.replace("  await send('Page.navigate',{url:pageUrl});", `
  await send('Page.addScriptToEvaluateOnNewDocument',{source:
    "window.axisLabels=[];const draw=CanvasRenderingContext2D.prototype.fillText;CanvasRenderingContext2D.prototype.fillText=function(t,...args){const r=this.canvas.getBoundingClientRect();if(r.width<150&&r.height>200&&/^\\\\d{5,}(?:[.,]\\\\d+)?$/.test(String(t)))window.axisLabels.push(Number(String(t).replace(',','.')));return draw.call(this,t,...args)};"});
  await send('Page.navigate',{url:pageUrl});`);
const begin = source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
const end = source.indexOf("  await send('Browser.close')", begin);
assert.ok(begin > 0 && end > begin);
source = source.slice(0, begin) + `
  const captures=[];
  const plot=await evaluate("(() => {const r=document.querySelector('[role=img]').getBoundingClientRect();return {right:r.right,top:r.top,height:r.height,left:r.left,width:r.width};})()");
  const axisX=plot.right-25, plotY=plot.top+plot.height/2;
  const capture=async name=>{
    await delay(500);
    const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    const path=outputFile.replace('initial.png',name+'.png');
    await writeFile(path,Buffer.from(shot.data,'base64'));captures.push(path);
  };
  const axis=async()=>await evaluate("(() => {const v=window.axisLabels.slice(-30);return v.length?{min:Math.min(...v),max:Math.max(...v)}:null})()");
  const redraw=async()=>{
    await evaluate('window.axisLabels=[]');
    await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:plot.left+plot.width/2,y:plotY});
    await delay(200);
  };
  await capture('initial');const initialAxis=await axis();
  // Price-axis drag switches native autoscale off. Horizontal zoom is manual too.
  await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:axisX,y:plotY});
  await send('Input.dispatchMouseEvent',{type:'mousePressed',x:axisX,y:plotY,button:'left',clickCount:1});
  await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:axisX,y:plotY+120,button:'left',buttons:1});
  await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:axisX,y:plotY+120,button:'left',clickCount:1});
  for(let z=0;z<3;z++) {await send('Input.dispatchMouseEvent',{type:'mouseWheel',x:plot.left+plot.width*0.7,y:plotY,deltaX:0,deltaY:-100});await delay(100);}
  await redraw(); await capture('manual');const manualAxis=await axis();
  await redraw();
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Próximo candle')).click()");
  await waitFor("document.body.textContent.includes('16/10/2026, 09:00:00')");
  await capture('next-manual');const nextAxis=await axis();
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Ajustar gráfico').click()");
  await redraw();await capture('adjusted');const adjustedAxis=await axis();
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Reiniciar')).click()");
  await waitFor("document.body.textContent.includes('15/10/2026, 18:20:00')");
  await redraw();await capture('reset');const resetAxis=await axis();
  const known=fixture.candles.slice(-40).map(c=>c.candle);
  const low=Math.min(...known.map(c=>c.low)),high=Math.max(...known.map(c=>c.high));
  const h1=fixture.structure.levels.filter(l=>l.timeframeMinutes===60).map(l=>l.price);
  const controls=await evaluate("({adjust:!!Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='Ajustar gráfico'),h1:document.body.textContent.includes('9 niveis exibidos H1')})");
  const checks={symbol:fixture.symbol,date:'2026-10-16',initialAxis,manualAxis,nextAxis,adjustedAxis,resetAxis,
    releasedCandles:fixture.candles.length,visibleCandleLow:low,visibleCandleHigh:high,h1Min:Math.min(...h1),h1Max:Math.max(...h1),controls,captures,errors,
    source:'real native chart, Java Replay prefix, API intercepted'};
  await writeFile(outputFile.replace('initial.png','browser.json'),JSON.stringify(checks,null,2));
  if(errors.length||!controls.adjust||!controls.h1
    ||!initialAxis||initialAxis.min<=Math.min(...h1)||initialAxis.max>=Math.max(...h1)
    ||JSON.stringify(manualAxis)!==JSON.stringify(nextAxis)
    ||JSON.stringify(initialAxis)!==JSON.stringify(resetAxis))throw new Error(JSON.stringify(checks));
  console.log(JSON.stringify(checks));
` + source.slice(end);
try { await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64')); }
catch (error) { console.error(error.message); process.exitCode = 1; }
