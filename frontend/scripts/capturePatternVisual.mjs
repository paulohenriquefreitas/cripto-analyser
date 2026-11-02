// Rendering demonstration only. Fixtures assign stages manually; no pattern is claimed detected.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../src/features/threecandles/validation/pattern-visual/',import.meta.url);
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
const pageUrl=process.argv[2]??'http://localhost:5173/three-candles';
for(const name of (process.env.CAPTURE_NAMES?.split(',')??['demo-buy-1','demo-buy-2','demo-buy-3','demo-sell-3'])) {
  process.argv=['node','capture',fileURLToPath(new URL(`${name}.json`,root)),fileURLToPath(new URL(`${name}.png`,root)),pageUrl];
  let source=template.replace("?? '2026-10-20'","?? '2026-10-30'")
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1450')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);')
    .replace(/  await waitFor\("document.body.textContent.includes\([^\n]+exibidos[^\n]+\n/,`  await waitFor("document.body.textContent.includes('Replay: PAUSED')");\n`);
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'"),end=source.indexOf("  await send('Browser.close')",begin);
  if(begin<0||end<0)throw new Error('Capture template changed');
  source=source.slice(0,begin)+`
  await evaluate("(() => {for(const label of document.querySelectorAll('[data-testid=chart-layers] label')) {const input=label.querySelector('input');if(input?.checked)input.click();} const banner=document.createElement('p');banner.textContent='DEMONSTRAÇÃO VISUAL — etapas atribuídas em fixture; regras reais de reconhecimento ainda pendentes.';banner.style.cssText='color:#fbbf24;font-weight:bold';document.querySelector('[data-testid=pattern-controls]')?.before(banner);})()");
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");
  await delay(500);
  const audit=await evaluate("document.querySelector('[data-testid=pattern-audit]')?.textContent");
  const controls=await evaluate("Array.from(document.querySelectorAll('[data-testid=pattern-controls] label')).map(l=>({text:l.textContent,checked:l.querySelector('input')?.checked}))");
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({audit,controls,errors,source:'Actual React/canvas with native candles and MANUALLY ASSIGNED visual stage fixtures; NOT detected patterns'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(outputFile);
`+source.slice(end);
  for(let attempt=0;attempt<3;attempt++) {
    try {await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${name}-${attempt}`).toString('base64'));break;}
    catch(e){console.error(e.message);if(attempt===2)throw new Error(`Capture failed ${name}`);}
  }
}
