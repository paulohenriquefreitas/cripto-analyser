import {readFile} from 'node:fs/promises';
const root=new URL(process.env.DOW_FIFTY_CAPTURE_ROOT??'dow-fifty/',import.meta.url);
const template=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
const checkpoints=JSON.parse(await readFile(new URL('checkpoints.json',root),'utf8'));
for(const kind of process.argv.length>2?process.argv.slice(2):checkpoints.map(c=>c.name)) {
  const data=JSON.parse(await readFile(new URL(kind+'.json',root),'utf8'));
  const date=new Date(data.availableAtMsc).toISOString().slice(0,10);
  process.argv=['node','capture',new URL(kind+'.json',root).pathname.replace(/^\/(.:)/,'$1'),new URL(kind+'.png',root).pathname.replace(/^\/(.:)/,'$1'),'http://127.0.0.1:5174/three-candles'];
  let source=template.replace("'2026-10-20'",JSON.stringify(date)).replace("liveSymbol:'WINZ26'","liveSymbol:fixture.symbol")
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1760,height:1500')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},60000);').replace('i<120;i++','i<600;i++');
  source=source.replace('socket.onmessage=event=>{','socket.onmessage=async event=>{');
  source=source.replace("      let data=fixture;",`
      if(path.includes('ThreeCandlesChart.tsx')) {
        const original=await (await fetch(request.request.url)).text();
        const modified=original.replace('const series = chart.addSeries','window.__dow50Chart = chart; const series = chart.addSeries')
          .replace('const m5 = new SwingPivotPrimitive','window.__dow50Series = series; const m5 = new SwingPivotPrimitive');
        if(modified===original)throw new Error('Native chart instrumentation location missing');
        void send('Fetch.fulfillRequest',{requestId:request.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/javascript'}],body:Buffer.from(modified).toString('base64')});return;
      }
      let data=fixture;`);
  source=source.replace("patterns:[{urlPattern:'*/api/three-candles/*'}]", "patterns:[{urlPattern:'*/api/three-candles/*'},{urlPattern:'*ThreeCandlesChart.tsx*'}]");
  source=source.replace("  await send('Page.navigate',{url:pageUrl});",`
  await send('Page.addScriptToEvaluateOnNewDocument',{source:"window.__dow50Labels=[];const fill=CanvasRenderingContext2D.prototype.fillText;CanvasRenderingContext2D.prototype.fillText=function(t,x,y,...rest){if(String(t).startsWith('DOW 50%'))window.__dow50Labels.push({text:t,x,y,width:this.lineWidth,color:this.strokeStyle});return fill.call(this,t,x,y,...rest)};"});
  void send('Page.navigate',{url:pageUrl}).catch(e=>console.error(e.message));`);
  if(data.replayMode==='PROGRESSIVE_M1') {
    source=source.replace("  const cursor=new Date(",`  await evaluate("Array.from(document.querySelectorAll('[role=combobox]')).find(e=>e.textContent.includes('Replay por candle fechado')).dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
  await waitFor("Array.from(document.querySelectorAll('[role=option]')).some(e=>e.textContent.includes('Replay progressivo M1'))");
  await evaluate("Array.from(document.querySelectorAll('[role=option]')).find(e=>e.textContent.includes('Replay progressivo M1')).click()");
  await delay(200);
  const cursor=new Date(`);
  }
  source=source.split('\n').map(line=>line.includes('niveis exibidos')?`  await waitFor("!!document.querySelector('[data-testid=dow-fifty-panel]')");`:line).join('\n');
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",begin);
  source=source.slice(0,begin)+`
  const fibBefore=await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent");
  const setting=async(label,checked)=>{await evaluate("(()=>{const input=Array.from(document.querySelectorAll('label')).find(l=>l.textContent.trim()==="+JSON.stringify(label)+").querySelector('input');if(input.checked!=="+checked+")input.click()})()");await delay(150);};
  await setting('Fibonacci',false);await setting('M5 - amarelo',false);await setting('H1 - fucsia',false);
  await setting('Mostrar Dow principal',true);await setting('Mostrar 50% Dow principal',true);
  await waitFor('!!window.__dow50Chart && !!window.__dow50Series');
  await evaluate('window.__dow50Chart.timeScale().fitContent()');
  await evaluate("document.querySelector('[data-testid=dow-fifty-panel]').scrollIntoView({block:'start'})");
  await delay(800);
  const current=fixture.dowFifty.references.find(r=>r.wave.id===fixture.dowFifty.currentWaveId);
  if(current) {
    await waitFor('window.__dow50Labels.length>0');
    const point=await evaluate("(()=>{const c=window.__dow50Chart,s=window.__dow50Series,r=document.querySelector('[role=img]').getBoundingClientRect();let x=c.timeScale().timeToCoordinate("+current.wave.recognizedAtMsc/1000+");if(x==null)x=c.timeScale().logicalToCoordinate("+fixture.candles.length+");return{x:r.left+(Math.max(0,x)+c.timeScale().width()-2)/2,y:r.top+s.priceToCoordinate("+current.wave.level50+")}})()");
    await send('Input.dispatchMouseEvent',{type:'mousePressed',x:point.x,y:point.y,button:'left',clickCount:1});
    await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y,button:'left',clickCount:1});
    await waitFor("!!document.querySelector('[data-testid=dow-fifty-selected]')");
  } else if(await evaluate('window.__dow50Labels.length'))throw new Error('White line appeared before a completed wave');
  await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:100,y:100});
  const labels=await evaluate('window.__dow50Labels');
  if(current) {
    const expected=await evaluate('(()=>{const ts=window.__dow50Chart.timeScale();return (ts.timeToCoordinate('+current.wave.initial.extremeTimeMsc/1000+')+ts.timeToCoordinate('+current.wave.terminal.extremeTimeMsc/1000+'))/2})()');
    const labelX=Math.max(4,Math.min(Math.max(0,expected)+6,await evaluate('window.__dow50Chart.timeScale().width()')-190));
    if(Math.abs(labels.at(-1).x-labelX)>2)throw new Error('Dow midpoint label detached from wave center');
  }

  if(labels.some(l=>l.width!==2.5 || l.color.toLowerCase()!=='#ffffff'))throw new Error('Wrong Dow 50 line style');
  if(fibBefore!==await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent"))throw new Error('Dow 50 changed Fibonacci');
  const evidence={symbol:fixture.symbol,atMsc:fixture.availableAtMsc,current:fixture.dowFifty.currentWaveId,state:fixture.dowFifty.state,
    label:labels.at(-1)??null,fibonacciPreserved:true,autoScale:await evaluate("window.__dow50Chart.priceScale('right').options().autoScale"),
    selected:await evaluate("document.querySelector('[data-testid=dow-fifty-selected]')?.textContent"),errors,
    source:'Real React/native chart with Java Replay snapshot over separate historical source; HTTP intercepted'};
  await delay(500);
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify(evidence,null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(outputFile);
`+source.slice(end);
  try {await import('data:text/javascript;base64,'+Buffer.from(source+'\n//'+kind).toString('base64'));}
  catch(e){console.error(kind+': '+e.message);process.exitCode=1;}
}
