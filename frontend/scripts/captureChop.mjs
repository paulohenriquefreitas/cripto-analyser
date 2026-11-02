// Actual Java CHOP values + native candles; presentation only, all three periods enabled.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../src/features/threecandles/validation/chop/',import.meta.url);
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
const pageUrl=process.argv[2]??'http://localhost:5173/three-candles';
for(const name of (process.env.CAPTURE_NAMES?.split(',')??['0950','1120','1127','1128','1130','1145','1215','1630'])) {
  process.argv=['node','capture',fileURLToPath(new URL(`${name}.json`,root)),fileURLToPath(new URL(`${name}.png`,root)),pageUrl];
  let source=template.replace("?? '2026-10-20'","?? '2026-10-30'")
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1500')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},60000);')
    .replace(/  await waitFor\("document.body.textContent.includes\([^\n]+exibidos[^\n]+\n/,`  await waitFor("document.body.textContent.includes('Replay: PAUSED')");\n`);
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'"),end=source.indexOf("  await send('Browser.close')",begin);
  source=source.slice(0,begin)+`
  const before=await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent==='Mostrar CHOP (experimental)').querySelector('input').checked");
  await evaluate("Array.from(document.querySelectorAll('label')).find(l=>l.textContent==='Mostrar CHOP (experimental)').querySelector('input').click()");
  await waitFor("!!document.querySelector('[data-testid=chop-panel]')");
  await evaluate("for(const label of document.querySelectorAll('[data-testid=chart-layers] label, [data-testid=pattern-controls] label')){const input=label.querySelector('input');if(input?.checked)input.click();}");
  await evaluate("document.querySelectorAll('[data-testid=chop-panel] input').forEach(i=>i.click());document.querySelector('[role=img][aria-label^=Candles]').style.height='420px'");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");
  await delay(600);
  const panel=await evaluate("document.querySelector('[data-testid=chop-panel]').textContent");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({initiallyEnabled:before,panel,errors,source:'Native Java CHOP snapshot; actual React panel; compact 420px candle viewport for capture only'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(outputFile);
`+source.slice(end);
  for(let attempt=0;attempt<3;attempt++) {
    try {await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${name}-${attempt}`).toString('base64'));break;}
    catch(error){console.error(`${name}: ${error.message}`);if(attempt===2)throw new Error(`Capture failed: ${name}`);}
  }
}
