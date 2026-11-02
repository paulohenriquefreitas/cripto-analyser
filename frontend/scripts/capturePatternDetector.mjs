// Real Java detector snapshots; focus only filters presentation, preserving the complete audit.
import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../src/features/threecandles/validation/pattern-detector/',import.meta.url);
const template=await readFile(new URL('captureThreeCandlesStructure.mjs',import.meta.url),'utf8');
const pageUrl=process.argv[2]??'http://localhost:5173/three-candles';
for(const name of (process.env.CAPTURE_NAMES?.split(',')??['0921','0926','0927','1120','1121','1123','1155'])) {
  const fixture=JSON.parse(await readFile(new URL(`${name}.json`,root),'utf8'));
  const buy=Number(name)>1100;
  const candidate=fixture.patternStudy.candidates.find(c=>c.direction===(buy?'BUY':'SELL') &&
    new Date(c.candle1.candle.bucketStartTimeMsc).toISOString().slice(11,16)===(name==='1155'?'11:45':buy?'11:10':'09:15') &&
    c.region.low===(name==='1155'?170415:buy?170115:170415));
  if(!candidate)throw new Error(`Missing case ${name}`);
  process.argv=['node','capture',fileURLToPath(new URL(`${name}.json`,root)),fileURLToPath(new URL(`${name}.png`,root)),pageUrl];
  let source=template.replace("?? '2026-10-20'","?? '2026-10-30'")
    .replace('width:1520,height:fixture.structure?.preparation?.experimental?1480:1100','width:1700,height:1450')
    .replace('i<100;i++','i<500;i++').replace('},10000);','},30000);')
    .replace(/  await waitFor\("document.body.textContent.includes\([^\n]+exibidos[^\n]+\n/,`  await waitFor("document.body.textContent.includes('Replay: PAUSED')");\n`);
  const begin=source.indexOf("  const screenshot=await send('Page.captureScreenshot'"),end=source.indexOf("  await send('Browser.close')",begin);
  if(begin<0||end<0)throw new Error('Capture template changed');
  source=source.slice(0,begin)+`
  await evaluate("document.querySelectorAll('[data-testid=pattern-display-controls] [role=combobox]')[1].dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}))");
  await delay(300);
  await evaluate(${JSON.stringify(`Array.from(document.querySelectorAll('[role=option]')).find(o=>o.getAttribute('data-value')===${JSON.stringify(candidate.id)}).click()`)});
  await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('Ajustar')).click()");
  await delay(500);
  const audit=await evaluate("document.querySelector('[data-testid=experimental-pattern-audit]').textContent");
  await evaluate("document.querySelector('[data-testid=advanced-settings]').open=false;window.scrollTo(0,0)");
  await delay(200);
  const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
  await writeFile(outputFile,Buffer.from(shot.data,'base64'));
  await writeFile(outputFile.replace('.png','-browser.json'),JSON.stringify({candidateId:${JSON.stringify(candidate.id)},audit,errors,source:'Native WINZ26 M1/M5; actual experimental detector; UI candidate focus only'},null,2));
  if(errors.length)throw new Error(JSON.stringify(errors));console.log(outputFile);
`+source.slice(end);
  await import('data:text/javascript;base64,'+Buffer.from(source+`\n// ${name}`).toString('base64'));
}
