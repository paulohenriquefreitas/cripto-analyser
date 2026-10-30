import {readFile} from 'node:fs/promises';
const root=new URL('oct28/',import.meta.url);
const template=await readFile(new URL('../../../../scripts/captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const kind of process.argv.slice(2).length?process.argv.slice(2):['session']) {
  process.argv=['node','capture',new URL(kind+'.json',root).pathname.replace(/^\/(.:)/,'$1'),new URL(kind+'.png',root).pathname.replace(/^\/(.:)/,'$1'),'http://127.0.0.1:5174/three-candles'];
  let source=template.replace("'2026-10-20'","'2026-10-28'").replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1920,height:1280');
  source=source.replace('i<100;i++','i<500;i++').replace('},10000);','},60000);').replace('i<120;i++','i<600;i++').replace('errors=${errors}', 'errors=${errors}; body=${await evaluate("document.body.innerText")}');
  source=source.replace("  await send('Page.navigate',{url:pageUrl});","  void send('Page.navigate',{url:pageUrl}).catch(e=>console.error(e.message));");
  source=source.split('\n').map(line=>line.includes('niveis exibidos') ? `  await waitFor("!!document.querySelector('[data-testid=dow-debug]')");` : line).join('\n');
  source=source.replace("      let data=fixture;",`
      if(path.includes('ThreeCandlesChart.tsx')) {
        const original=await (await fetch(request.request.url)).text();
        const modified=original.replace('const series = chart.addSeries','window.__oct28Chart = chart; const series = chart.addSeries');
        if(modified===original)throw new Error('Chart instrumentation location not found');
        void send('Fetch.fulfillRequest',{requestId:request.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:'application/javascript'}],body:Buffer.from(modified).toString('base64')});
        return;
      }
      let data=fixture;`);
  source=source.replace("patterns:[{urlPattern:'*/api/three-candles/*'}]", "patterns:[{urlPattern:'*/api/three-candles/*'},{urlPattern:'*ThreeCandlesChart.tsx*'}]");
  source=source.replace('socket.onmessage=event=>{','socket.onmessage=async event=>{');
  const start=source.indexOf("  const screenshot=await send('Page.captureScreenshot'");
  const end=source.indexOf("  await send('Browser.close')",start);
  source=source.slice(0,start)+`
  for(const label of ['Fibonacci','H1 -','M5 -','Mostrar ondas Dow micro','Mostrar todos os fractais micro confirmados']) {
    await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent.includes("+JSON.stringify(label)+")).querySelector('input').click()");
    await delay(300);
  }
  await evaluate("document.querySelector('[role=img]').insertAdjacentHTML('beforebegin','<p>WINZ26 / MT5 nativo / 28-10-2026 / M5 fechado 09:00-17:30 / conhecido ate 17:35</p>')");
  await evaluate("document.querySelector('[role=img]').scrollIntoView({block:'center'})");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='Ajustar gráfico').click()");
  await waitFor('!!window.__oct28Chart');
  await evaluate('window.__oct28Chart.timeScale().fitContent()');
  await delay(800);
  const body=await evaluate('document.body.textContent');
  const coverage=await evaluate('({range:window.__oct28Chart.timeScale().getVisibleRange(),firstX:window.__oct28Chart.timeScale().timeToCoordinate('+fixture.candles[0].candle.bucketStartTimeMsc/1000+'),lastX:window.__oct28Chart.timeScale().timeToCoordinate('+fixture.candles.at(-1).candle.bucketStartTimeMsc/1000+')})');
  if(coverage.firstX==null||coverage.firstX<0||coverage.lastX==null)throw new Error('Full session coverage not demonstrated');
  const fibState=await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({errors,body,coverage,fibState,fixture:fixture.symbol,cursor:fixture.availableAtMsc,source:'Real React chart with exported Java replay response; HTTP intercepted'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));
  for(const [name,from,to] of [['initial','09:00','11:00'],['central','11:00','14:00'],['right','14:00','17:35']]) {
    const day=new Date(fixture.availableAtMsc).toISOString().slice(0,10);
    const range={from:Date.parse(day+'T'+from+':00Z')/1000,to:Date.parse(day+'T'+to+':00Z')/1000};
    await evaluate('window.__oct28Chart.timeScale().setVisibleRange('+JSON.stringify(range)+')');
    await delay(400);
    const detail=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
    await writeFile(outputFile.replace('.png','-'+name+'.png'),Buffer.from(detail.data,'base64'));
  }
  if(fibState!==await evaluate("document.querySelector('[data-testid=fibonacci-current]').textContent"))throw new Error('Viewport changed Fibonacci state');
  console.log(outputFile);
`+source.slice(end);
  try { await import('data:text/javascript;base64,'+Buffer.from(source+'\n//'+kind).toString('base64')); } catch(e) { console.error(e.message);process.exitCode=1; }
}
