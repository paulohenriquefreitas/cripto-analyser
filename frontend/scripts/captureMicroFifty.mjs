import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const phase=process.argv[2]??'after',url=process.argv[3]??'http://localhost:5173/three-candles';
assert.ok(['before','after'].includes(phase));
const root=new URL('../src/features/threecandles/validation/micro-fifty/',import.meta.url);
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
for(const name of (process.env.CAPTURE_NAMES?.split(',')??['29-1600','29-1610','30-1015','30-1025'])) {
  process.argv=['node','capture',fileURLToPath(new URL(`${name}-${phase}.json`,root)),fileURLToPath(new URL(`${name}-${phase}.png`,root)),url];
  let source=template.replace("?? '2026-10-20'",`?? '2026-10-${name.slice(0,2)}'`)
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1400')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);')
    .replace(/  await waitFor\("document.body.textContent.includes\([^\n]+exibidos[^\n]+\n/,`  await waitFor("document.body.textContent.includes('Replay: PAUSED')");\n`);
  source=source.replace('errors=${errors}', 'errors=${errors}; body=${await evaluate("document.body.innerText")}');
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'"),end=source.indexOf("  await send('Browser.close')",begin);
  assert.ok(begin>0&&end>begin);
  source=source.slice(0,begin)+`
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");
  await delay(500);
  const labels=await evaluate("Array.from(document.querySelectorAll('[data-testid=chart-layers] label')).map(l=>({text:l.textContent,checked:l.querySelector('input')?.checked}))");
  const summary=await evaluate("document.querySelector('[data-testid=structure-summary]').innerText");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({labels,summary,errors,source:'Actual React/canvas, native MT5 Java snapshots via intercepted API; unchanged cursor and viewport'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));
  console.log(outputFile);
`+source.slice(end);
  for(let attempt=0;attempt<3;attempt++) {
    try {await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${name}-${phase}-${attempt}`).toString('base64'));break;}
    catch(e){console.error(e.message);if(attempt===2)throw new Error(`Capture failed ${name}-${phase}`);}
  }
}
