import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const directory=fileURLToPath(new URL('fibonacci/',import.meta.url));await mkdir(directory,{recursive:true});
process.argv=['node','capture',directory+'1000.json',directory+'initial.png',process.argv[2]??'http://localhost:5173/three-candles'];
let source=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
source=source.replace("const fixture=JSON.parse(await readFile(fixtureFile,'utf8'));", `
const snapshots={};for(const clock of ['1000','1100','1200'])snapshots[clock]=JSON.parse(await readFile(fixtureFile.replace('1000.json',clock+'.json'),'utf8'));
let fixture=snapshots['1000'];
`);
source=source.replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:2100');
source=source.replace('      let data=fixture;',`
      if(path.endsWith('/commands')) {
        const action=JSON.parse(request.request.postData||'{}').action;
        if(action==='NEXT')fixture=fixture.availableAtMsc===snapshots['1000'].availableAtMsc?snapshots['1100']:snapshots['1200'];
        if(action==='RESET')fixture=snapshots['1000'];
      }
      let data=fixture;
`);
source=source.replace("  await send('Page.navigate',{url:pageUrl});",`
  await send('Page.addScriptToEvaluateOnNewDocument',{source:
    "window.fiboCircles=[];const arc=CanvasRenderingContext2D.prototype.arc;CanvasRenderingContext2D.prototype.arc=function(x,y,r,...args){if(r===5){const b=this.canvas.getBoundingClientRect();if(b.width>300&&x>=0&&y>=0&&x<=b.width&&y<=b.height)window.fiboCircles.push({x:b.left+x,y:b.top+y});}return arc.call(this,x,y,r,...args)};"});
  await send('Page.navigate',{url:pageUrl});`);
const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
const end=source.indexOf("  await send('Browser.close')",begin);assert.ok(begin>0&&end>begin);
source=source.slice(0,begin)+`
  const saved=[];
  const text=async()=>await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent");
  const capture=async name=>{await delay(600);const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});const path=outputFile.replace('initial.png',name+'.png');await writeFile(path,Buffer.from(shot.data,'base64'));saved.push({name,path,fibonacci:await text()});};
  const clickButton=async name=>{await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes("+JSON.stringify(name)+")).click()");await delay(350);};
  await waitFor("document.querySelector('[data-testid=fibonacci-current]').textContent.includes('209.025')");
  await capture('initial');
  await clickButton('Próximo candle');
  await waitFor("document.querySelector('[data-testid=fibonacci-current]').textContent.includes('209.007,5')");
  await capture('expanded');
  // Select actual plotted circles by real mouse clicks, not just selector values.
  const pick=async(type,last)=>{
    await evaluate('window.fiboCircles=[]');
    await clickButton(type==='TOP'?'Selecionar máxima no gráfico':'Selecionar mínima no gráfico');
    await waitFor('window.fiboCircles.length>0');
    const points=await evaluate('window.fiboCircles');const p=last?points.at(-1):points[0];
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:p.x,y:p.y,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:p.x,y:p.y,button:'left',clickCount:1});
    await delay(400);
  };
  await pick('TOP',true);await pick('BOTTOM',false);
  await clickButton('Aplicar Fibonacci manual');
  await waitFor("document.querySelector('[data-testid=fibonacci-current]').textContent.includes('209.442,5')");
  await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.includes('Comparar manual e automático')).querySelector('input').click()");
  await capture('manual');
  const manualBefore=await text();await clickButton('Próximo candle');
  await waitFor("document.body.textContent.includes('16/10/2026, 11:55:00')");
  if(await text()!==manualBefore)throw new Error('Automatic update overwrote manual Fibonacci');
  await capture('manual-preserved');
  // Switch only the independent Fibonacci scope/mode, leaving structural visibility alone.
  const choose=async(label,option)=>{
    await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent==="+JSON.stringify(label)+").parentElement.querySelector('[role=combobox]').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
    await waitFor("Array.from(document.querySelectorAll('[role=option]')).some(o=>o.textContent==="+JSON.stringify(option)+")");
    await evaluate("Array.from(document.querySelectorAll('[role=option]')).find(o=>o.textContent==="+JSON.stringify(option)+").click()");await delay(350);
  };
  await choose('Modo Fibonacci','Automático experimental');await choose('Amplitude Fibonacci','Macro H1 2+2');
  await capture('macro');
  await choose('Amplitude Fibonacci','Micro M5');await choose('Modo Fibonacci','Manual');
  await clickButton('Reiniciar');
  await waitFor("document.querySelector('[data-testid=fibonacci-current]').textContent.includes('aguardando duas âncoras')");
  await capture('reset-pending-manual');
  await clickButton('Próximo candle');
  await waitFor("document.querySelector('[data-testid=fibonacci-current]').textContent.includes('209.442,5')");
  const restored=await text();
  const summary={saved,manualGraphSelection:true,manualPreserved:manualBefore===restored,resetDoesNotBackdate:true,errors,source:'real frontend, Java Replay prefix snapshots; API intercepted'};
  if(errors.length)throw new Error(JSON.stringify(summary));
  await writeFile(outputFile.replace('initial.png','browser.json'),JSON.stringify(summary,null,2));console.log(JSON.stringify(summary));
`+source.slice(end);
try{await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));}
catch(error){console.error(error.message);process.exitCode=1;}
